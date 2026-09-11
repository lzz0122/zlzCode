package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.context.ReActContext;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.tool.model.ApprovalRequired;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
final class AgentReActLoop {

    private final OpenAiChatClient openAiChatClient;
    private final ToolRegistry toolRegistry;
    private final LlmTurnStreamProcessor turnStreamProcessor;

    AgentReActLoop(
            OpenAiChatClient openAiChatClient,
            ToolRegistry toolRegistry,
            LlmTurnStreamProcessor turnStreamProcessor) {
        this.openAiChatClient = openAiChatClient;
        this.toolRegistry = toolRegistry;
        this.turnStreamProcessor = turnStreamProcessor;
    }

    Flux<AgentEvent> run(
            ReActContext context,
            ToolApprovalCoordinator approvalCoordinator) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"), new AgentEvent.Status("正在分析")),
                Flux.just(RunLoopState.initial(context))
                        .expandDeep(state -> advanceRunState(state, approvalCoordinator))
                        .concatMapIterable(RunLoopState::pendingEvents));
    }

    /*
     * 背景：ReAct 每一步都可能触发异步模型或工具操作，分散的递归流难以审查合法终态。
     * 设计意图：把每个 phase 的唯一后继集中在一个推进器中，事件只从状态快照投影出去。
     * 关键约束：只能沿枚举定义的方向前进，ANSWER_READY 是循环的唯一终点，持久化由 Executor 负责。
     */
    private Publisher<? extends RunLoopState> advanceRunState(
            RunLoopState state,
            ToolApprovalCoordinator approvalCoordinator) {
        return switch (state.phase()) {
            case INITIAL -> Mono.just(state.transitionTo(RunPhase.REQUEST_MODEL));
            case REQUEST_MODEL -> collectModelTurn(state.context())
                    .map(result -> state.withModelResult(RunPhase.MODEL_RESULT_READY, result));
            case MODEL_RESULT_READY -> handleModelTurn(state);
            case TOOL_READY -> executeToolCall(state, approvalCoordinator);
            case TOOL_RESULT_READY -> Mono.just(advanceToolBatch(state));
            case ANSWER_READY -> Mono.empty();
        };
    }

    private Mono<LlmTurnResult> collectModelTurn(ReActContext context) {
        return turnStreamProcessor.collect(openAiChatClient.chat(
                context.execution().options().openai(),
                buildLlmRequest(
                        context,
                        context.messagesSnapshot(),
                        toolRegistry.modelToolDeclarations())));
    }

    /*
     * 背景：模型结果可能是最终文本，也可能是需要本机执行的工具调用，二者对历史消息和事件顺序要求不同。
     * 设计意图：先记录模型 Turn，再把结果分类为回答、工具或非法响应，避免在多个分支重复计数。
     * 关键约束：工具调用必须先写入 AssistantToolCalls 并发出 ToolStarted，最终文本不能带着未完成工具调用写入。
     */
    private Publisher<? extends RunLoopState> handleModelTurn(RunLoopState state) {
        ReActContext context = state.context();
        LlmTurnResult result = state.modelTurnResult();
        context.recordModelTurn(result);
        ModelTurnOutcome decision = classifyModelTurn(result);
        if (decision instanceof ModelTurnOutcome.InvalidResponse invalid) {
            return Mono.error(invalid.error());
        }
        if (decision instanceof ModelTurnOutcome.AnswerReady answer) {
            context.appendFinalAssistant(answer.content());
            return Mono.just(state.withEvents(
                    RunPhase.ANSWER_READY,
                    List.of(new AgentEvent.TextDelta(answer.content()))));
        }

        ModelTurnOutcome.ToolRequested execute = (ModelTurnOutcome.ToolRequested) decision;
        List<LlmToolCall> calls = execute.calls();
        context.appendAssistantToolCalls(
                result.content(), result.hiddenReasoning(), calls);
        LlmToolCall firstCall = calls.getFirst();
        return Mono.just(state.withPendingTool(
                RunPhase.TOOL_READY,
                calls,
                0,
                List.of(new AgentEvent.ToolStarted(
                        firstCall.id(), toolRegistry.displayName(firstCall.name()), null))));
    }

    /*
     * 背景：模型可以在同一 Assistant Turn 中返回一组相互独立的工具调用。
     * 设计意图：完整接收模型原始顺序，由 ReAct 状态机串行执行，不额外引入调用额度策略。
     * 关键约束：整组调用必须登记在同一条 AssistantToolCalls 消息中，不能截取首项或在结果未齐时请求下一轮模型，否则历史会出现悬空 call ID。
     */
    private ModelTurnOutcome classifyModelTurn(LlmTurnResult result) {
        if (result.hasToolCalls()) {
            return new ModelTurnOutcome.ToolRequested(result.toolCalls());
        }
        if (result.content() == null || result.content().isBlank()) {
            return new ModelTurnOutcome.InvalidResponse(OpenAiIntegrationException.noDisplayableResponse());
        }
        return new ModelTurnOutcome.AnswerReady(result.content());
    }

    /*
     * 背景：同一模型 Turn 的工具结果必须全部补齐，下一轮模型请求才能获得合法消息历史。
     * 设计意图：当前调用收口后只推进一个索引；仍有调用时启动下一项，整组完成后才回到模型。
     * 关键约束：索引只能在最终 ToolOutcome 已写入 Context 后递增；若在审批决定或工具启动时提前推进，会造成重复提交、结果错配或越过尚未完成的调用。
     */
    private RunLoopState advanceToolBatch(RunLoopState state) {
        int nextIndex = state.activeToolIndex() + 1;
        if (nextIndex < state.toolCalls().size()) {
            LlmToolCall nextCall = state.toolCalls().get(nextIndex);
            return state.withPendingTool(
                    RunPhase.TOOL_READY,
                    state.toolCalls(),
                    nextIndex,
                    List.of(new AgentEvent.ToolStarted(
                            nextCall.id(), toolRegistry.displayName(nextCall.name()), null)));
        }
        return state.withEvents(
                RunPhase.REQUEST_MODEL,
                List.of(new AgentEvent.Status("正在整理结果")));
    }

    /*
     * 背景：工具结果既要形成前端轨迹事件，也要成为下一轮模型可见的 ToolResult 消息。
     * 设计意图：先由 Context 原子记录执行结果，再推进到下一次模型请求，避免两套历史分叉。
     * 关键约束：ToolFinished 必须先于下一轮请求；执行异常不能伪造成功结果或继续发送 Completed。
     */
    private Publisher<? extends RunLoopState> executeToolCall(
            RunLoopState state,
            ToolApprovalCoordinator approvalCoordinator) {
        LlmToolCall call = state.activeToolCall();
        ToolExecutionContext executionContext = new ToolExecutionContext(
                state.context().execution().runId(),
                call.id(),
                state.context().workspace());
        return toolRegistry.execute(
                        call.name(), executionContext, call.arguments())
                .flatMap(result -> {
                    if (result instanceof ToolCompleted completed) {
                        return Mono.just(completed.outcome());
                    }
                    ApprovalRequired approval = (ApprovalRequired) result;
                    return requireOutcome(
                            approvalCoordinator.awaitOutcome(executionContext, approval.plan()));
                })
                .map(outcome -> {
                    state.context().recordToolExecution(call, outcome);
                    return state.withToolOutcomeEvents(
                            RunPhase.TOOL_RESULT_READY,
                            List.of(new AgentEvent.ToolFinished(
                                    call.id(),
                                    outcome.ok() ? "completed" : "failed",
                                    outcome.presentation())));
                });
    }

    /*
     * 背景：修改工具的中间计划不能写入模型历史，ReAct 只能接收审批流程的最终工具结果。
     * 设计意图：在单一工具记录点之前拒绝空协调流，而不为审批分支建立第二套历史逻辑。
     * 关键约束：Coordinator 必须产生且只产生一个 ToolOutcome；空 Mono 或空结果必须终止 Run，否则未完成调用会被误当成可继续状态。
     */
    private Mono<ToolOutcome> requireOutcome(Mono<ToolOutcome> outcome) {
        if (outcome == null) {
            return Mono.error(new IllegalStateException(
                    "Tool approval coordinator returned no outcome"));
        }
        return outcome.switchIfEmpty(Mono.error(new IllegalStateException(
                "Tool approval coordinator returned no outcome")));
    }

    private LlmRequest buildLlmRequest(
            ReActContext context,
            List<com.zlzcode.codeagent.agent.model.LlmMessage> messages,
            List<LlmRequest.ToolDeclaration> availableTools) {
        return new LlmRequest(
                context.execution().options().model(),
                context.execution().options().reasoningEffort(),
                messages,
                availableTools);
    }

    private enum RunPhase {
        INITIAL,
        REQUEST_MODEL,
        MODEL_RESULT_READY,
        TOOL_READY,
        TOOL_RESULT_READY,
        ANSWER_READY
    }

    private sealed interface ModelTurnOutcome
            permits ModelTurnOutcome.ToolRequested, ModelTurnOutcome.AnswerReady,
            ModelTurnOutcome.InvalidResponse {

        record ToolRequested(List<LlmToolCall> calls) implements ModelTurnOutcome {

            public ToolRequested {
                calls = List.copyOf(calls);
            }
        }

        record AnswerReady(String content) implements ModelTurnOutcome {
        }

        record InvalidResponse(RuntimeException error) implements ModelTurnOutcome {
        }
    }

    private record RunLoopState(
            ReActContext context,
            RunPhase phase,
            LlmTurnResult modelTurnResult,
            List<LlmToolCall> toolCalls,
            int activeToolIndex,
            List<AgentEvent> pendingEvents) {

        /*
         * 背景：状态机不同阶段携带的数据不同，Java record 需要用可空字段表达尚未产生的结果。
         * 设计意图：通过各个工厂方法集中构造状态，保持 phase 与 payload 的更新同步。
         * 关键约束：新增 phase 时必须同步定义其必需字段和唯一转移，不能直接读取其他阶段的可空数据。
         */
        private RunLoopState {
            toolCalls = List.copyOf(toolCalls);
            pendingEvents = List.copyOf(pendingEvents);
        }

        private static RunLoopState initial(ReActContext context) {
            return new RunLoopState(
                    context,
                    RunPhase.INITIAL,
                    null,
                    List.of(),
                    0,
                    List.of());
        }

        private RunLoopState transitionTo(RunPhase nextPhase) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, toolCalls, activeToolIndex, List.of());
        }

        private RunLoopState withModelResult(RunPhase nextPhase, LlmTurnResult nextResult) {
            return new RunLoopState(context, nextPhase, nextResult, List.of(), 0, List.of());
        }

        private RunLoopState withEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, toolCalls, activeToolIndex, nextEvents);
        }

        private RunLoopState withPendingTool(
                RunPhase nextPhase,
                List<LlmToolCall> nextCalls,
                int nextToolIndex,
                List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, nextCalls, nextToolIndex, nextEvents);
        }

        private RunLoopState withToolOutcomeEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, toolCalls, activeToolIndex, nextEvents);
        }

        private LlmToolCall activeToolCall() {
            if (toolCalls.isEmpty() || activeToolIndex < 0 || activeToolIndex >= toolCalls.size()) {
                throw new IllegalStateException("Run loop has no active tool call");
            }
            return toolCalls.get(activeToolIndex);
        }
    }
}
