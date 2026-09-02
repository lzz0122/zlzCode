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

    Flux<AgentEvent> run(ReActContext context) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"), new AgentEvent.Status("正在分析")),
                Flux.just(RunLoopState.initial(context))
                        .expandDeep(this::advanceRunState)
                        .concatMapIterable(RunLoopState::pendingEvents));
    }

    /*
     * 背景：ReAct 每一步都可能触发异步模型或工具操作，分散的递归流难以审查合法终态。
     * 设计意图：把每个 phase 的唯一后继集中在一个推进器中，事件只从状态快照投影出去。
     * 关键约束：只能沿枚举定义的方向前进，ANSWER_READY 是循环的唯一终点，持久化由 Executor 负责。
     */
    private Publisher<? extends RunLoopState> advanceRunState(RunLoopState state) {
        return switch (state.phase()) {
            case INITIAL -> Mono.just(state.transitionTo(RunPhase.REQUEST_MODEL));
            case REQUEST_MODEL -> collectModelTurn(state.context())
                    .map(result -> state.withModelResult(RunPhase.MODEL_RESULT_READY, result));
            case MODEL_RESULT_READY -> handleModelTurn(state);
            case TOOL_READY -> executeToolCall(state);
            case TOOL_RESULT_READY -> Mono.just(state.transitionTo(RunPhase.REQUEST_MODEL));
            case ANSWER_READY -> Mono.empty();
        };
    }

    private Mono<LlmTurnResult> collectModelTurn(ReActContext context) {
        List<LlmRequest.ToolDeclaration> tools = context.canExecuteTool()
                ? toolRegistry.modelToolDeclarations()
                : List.of();
        return turnStreamProcessor.collect(openAiChatClient.chat(
                context.execution().options().openai(),
                buildLlmRequest(context, context.messagesSnapshot(), tools)));
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
        ModelTurnOutcome decision = classifyModelTurn(context, result);
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
        LlmToolCall call = execute.call();
        context.appendAssistantToolCalls(
                result.content(), result.hiddenReasoning(), List.of(call));
        return Mono.just(state.withPendingTool(
                RunPhase.TOOL_READY,
                call,
                List.of(new AgentEvent.ToolStarted(
                        call.id(), toolRegistry.displayName(call.name()), null))));
    }

    /*
     * 背景：工具额度和工具注册状态是模型进入本机执行边界前的最后校验。
     * 设计意图：已注册工具的业务失败交给下一轮模型修正，协议非法或额度耗尽则终止 Run。
     * 关键约束：当前实现每轮最多接受一个工具调用；多工具调用协议另行设计，不能在此次结构重构中改变。
     */
    private ModelTurnOutcome classifyModelTurn(ReActContext context, LlmTurnResult result) {
        if (result.hasToolCalls()) {
            if (result.toolCalls().size() != 1 || !context.canExecuteTool()) {
                return new ModelTurnOutcome.InvalidResponse(OpenAiIntegrationException.invalidToolCall());
            }
            return new ModelTurnOutcome.ToolRequested(result.toolCalls().getFirst());
        }
        if (result.content() == null || result.content().isBlank()) {
            return new ModelTurnOutcome.InvalidResponse(OpenAiIntegrationException.noDisplayableResponse());
        }
        return new ModelTurnOutcome.AnswerReady(result.content());
    }

    /*
     * 背景：工具结果既要形成前端轨迹事件，也要成为下一轮模型可见的 ToolResult 消息。
     * 设计意图：先由 Context 原子记录执行结果，再推进到下一次模型请求，避免两套历史分叉。
     * 关键约束：ToolFinished 必须先于下一轮请求；执行异常不能伪造成功结果或继续发送 Completed。
     */
    private Publisher<? extends RunLoopState> executeToolCall(RunLoopState state) {
        return toolRegistry.execute(
                        state.toolCall().name(), state.context().workspace(), state.toolCall().arguments())
                .map(outcome -> {
                    state.context().recordToolExecution(state.toolCall(), outcome);
                    return state.withToolOutcomeEvents(
                            RunPhase.TOOL_RESULT_READY,
                            List.of(
                                    new AgentEvent.ToolFinished(
                                            state.toolCall().id(),
                                            outcome.ok() ? "completed" : "failed",
                                            outcome.presentation()),
                                    new AgentEvent.Status("正在整理结果")));
                });
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

        record ToolRequested(LlmToolCall call) implements ModelTurnOutcome {
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
            LlmToolCall toolCall,
            List<AgentEvent> pendingEvents) {

        /*
         * 背景：状态机不同阶段携带的数据不同，Java record 需要用可空字段表达尚未产生的结果。
         * 设计意图：通过各个工厂方法集中构造状态，保持 phase 与 payload 的更新同步。
         * 关键约束：新增 phase 时必须同步定义其必需字段和唯一转移，不能直接读取其他阶段的可空数据。
         */
        private RunLoopState {
            pendingEvents = List.copyOf(pendingEvents);
        }

        private static RunLoopState initial(ReActContext context) {
            return new RunLoopState(
                    context,
                    RunPhase.INITIAL,
                    null,
                    null,
                    List.of());
        }

        private RunLoopState transitionTo(RunPhase nextPhase) {
            return new RunLoopState(context, nextPhase, modelTurnResult, toolCall, List.of());
        }

        private RunLoopState withModelResult(RunPhase nextPhase, LlmTurnResult nextResult) {
            return new RunLoopState(context, nextPhase, nextResult, null, List.of());
        }

        private RunLoopState withEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(context, nextPhase, modelTurnResult, toolCall, nextEvents);
        }

        private RunLoopState withPendingTool(
                RunPhase nextPhase,
                LlmToolCall nextCall,
                List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, nextCall, nextEvents);
        }

        private RunLoopState withToolOutcomeEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, toolCall, nextEvents);
        }
    }
}
