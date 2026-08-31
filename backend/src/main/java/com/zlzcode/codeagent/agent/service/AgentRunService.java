package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.context.AgentRunContext;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.reactivestreams.Publisher;

@Service
public class AgentRunService {

    private final OpenAiChatClient openAiChatClient;
    private final WorkspaceRegistry workspaceRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentRunExceptionMapper agentRunExceptionMapper;
    private final LlmTurnStreamProcessor turnStreamProcessor;
    private final ConversationHistoryBuilder conversationHistoryBuilder;
    private final SessionService sessionService;
    private final ToolDefinition workspaceToolDefinition;

    public AgentRunService(
            OpenAiChatClient openAiChatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper agentRunExceptionMapper,
            LlmTurnStreamProcessor turnStreamProcessor,
            ConversationHistoryBuilder conversationHistoryBuilder,
            SessionService sessionService,
            ToolDefinition workspaceToolDefinition) {
        this.openAiChatClient = openAiChatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.agentRunExceptionMapper = agentRunExceptionMapper;
        this.turnStreamProcessor = turnStreamProcessor;
        this.conversationHistoryBuilder = conversationHistoryBuilder;
        this.sessionService = sessionService;
        this.workspaceToolDefinition = workspaceToolDefinition;
    }

    //TODO
    public Flux<AgentEvent> run(AgentRunRequest request) {
        /*
         * 背景：Agent 通过 SSE 向前端持续发送事件，内部异常若直接逃逸会中断 HTTP 响应并暴露实现细节。
         * 设计意图：在服务边界把已知异常映射为稳定的公开错误事件，而不是把堆栈交给 Web 层处理。
         * 关键约束：错误事件必须终止本次运行，且消息中不得包含密钥、本机路径或内部异常堆栈。
         */
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            String runId = "run-" + UUID.randomUUID();
            return Mono.fromCallable(() -> sessionService.beginRun(
                            request.sessionId(), runId, request.prompt()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(sessionRun -> Flux.concat(
                            Flux.just(new AgentEvent.RunStarted(sessionRun.runId())),
                            initializeRunExecution(request, sessionRun, startedAt)));
        }).onErrorResume(agentRunExceptionMapper::mapException);
    }

    private Flux<AgentEvent> initializeRunExecution(
            AgentRunRequest request,
            SessionService.RunSession sessionRun,
            long startedAt) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"),
                        new AgentEvent.Status("正在分析")),
                Mono.fromCallable(() -> workspaceRegistry.resolve(sessionRun.workspaceId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(workspace -> {
                            List<LlmMessage> initialMessages = conversationHistoryBuilder.build(
                                    AgentLlmContract.systemPrompt(workspaceToolDefinition.name()),
                                    sessionRun.completedHistory(),
                                    sessionRun.prompt());
                            AgentRunContext context = new AgentRunContext(
                                    new AgentRunContext.RunConfiguration(
                                            request.model(),
                                            request.reasoningEffort(),
                                            request.maxToolCalls(),
                                            request.openai()),
                                    sessionRun,
                                    workspace,
                                    initialMessages,
                                    startedAt);
                            return processRunToCompletion(context);
                        })
        );
    }

    /*
     * 背景：ReAct Run 需要在每轮模型结果、工具观察结果和最终回答之间循环推进，
     * 递归拼接事件流会让结束条件和下一轮入口分散在多个方法中。
     * 设计意图：用单一状态推进器表达 Reason -> Act -> Observe -> Reason，
     * 每个状态只产生一次异步转移，事件由状态统一投影。
     * 关键约束：状态必须按顺序推进，ToolStarted 先于工具执行，Completed 只能来自终态。
     */
    private Flux<AgentEvent> processRunToCompletion(AgentRunContext context) {
        return Flux.just(RunLoopState.initial(context))
                .expandDeep(this::advanceRunState)
                .concatMapIterable(RunLoopState::pendingEvents);
    }

    private Publisher<? extends RunLoopState> advanceRunState(RunLoopState state) {
        return switch (state.phase()) {
            case INITIAL -> Mono.just(state.transitionTo(RunPhase.REQUEST_MODEL));
            case REQUEST_MODEL -> collectModelTurn(state.context())
                    .map(result -> state.withModelResult(RunPhase.MODEL_RESULT_READY, result));
            case MODEL_RESULT_READY -> handleModelTurn(state);
            case TOOL_READY -> executeToolCall(state);
            case TOOL_RESULT_READY -> Mono.just(state.transitionTo(RunPhase.REQUEST_MODEL));
            case ANSWER_READY -> Mono.just(state.transitionTo(RunPhase.PERSIST_COMPLETION));
            case PERSIST_COMPLETION -> persistRunCompletion(state);
            case COMPLETED -> Mono.empty();
        };
    }

    private Mono<LlmTurnResult> collectModelTurn(AgentRunContext context) {
        List<LlmRequest.ToolDeclaration> tools = context.canExecuteTool()
                ? List.of(new LlmRequest.ToolDeclaration(
                workspaceToolDefinition.name(),
                workspaceToolDefinition.description(),
                workspaceToolDefinition.parametersSchema()))
                : List.of();
        return turnStreamProcessor.collect(openAiChatClient.chat(
                context.configuration().openai(),
                buildLlmRequest(context, context.messages(), tools)));
    }

    private Publisher<? extends RunLoopState> handleModelTurn(RunLoopState state) {
        AgentRunContext context = state.context();
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
        ToolRegistry.RegisteredTool tool = toolRegistry.find(call.name());
        return Mono.just(state.withPendingTool(
                RunPhase.TOOL_READY,
                call,
                tool,
                List.of(new AgentEvent.ToolStarted(
                        call.id(), tool.displayName(), null))));
    }

    private ModelTurnOutcome classifyModelTurn(AgentRunContext context, LlmTurnResult result) {
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

    private Publisher<? extends RunLoopState> executeToolCall(RunLoopState state) {
        return toolRegistry.execute(
                        state.registeredTool(), state.context().workspace(), state.toolCall().arguments())
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

    /*
     * 背景：前端收到 completed 后会把本轮视为可进入下一轮的稳定历史，不能先于 Session 文件落盘。
     * 设计意图：在 Agent 编排边界等待完整 Turn 原子保存，再创建终态事件；不让流处理器直接宣布成功。
     * 关键约束：持久化失败必须转成 Error 并保留 incomplete Turn，绝不能继续发送 completed。
     */
    private Publisher<? extends RunLoopState> persistRunCompletion(RunLoopState state) {
        AgentRunContext context = state.context();
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        context.sessionRun(), state.modelTurnResult().content(), context.sessionToolHistory()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(state.withEvents(RunPhase.COMPLETED, List.of(buildCompletedEvent(context))));
    }

    private AgentEvent.Completed buildCompletedEvent(AgentRunContext context) {
        long durationMs = Math.max(1L, Duration.ofNanos(
                System.nanoTime() - context.startedAtNanos()).toMillis());
        List<AgentEvent.ToolHistory> eventTools = context.toolExecutions().stream()
                .map(record -> new AgentEvent.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        return new AgentEvent.Completed(
                new AgentEvent.RunMetrics(
                        context.modelSteps(), durationMs,
                        context.inputTokens(), context.outputTokens()),
                eventTools);
    }

    private LlmRequest buildLlmRequest(
            AgentRunContext context,
            List<LlmMessage> messages,
            List<LlmRequest.ToolDeclaration> availableTools) {
        return new LlmRequest(
                context.configuration().model(),
                context.configuration().reasoningEffort(),
                messages,
                availableTools);
    }

    private enum RunPhase {
        INITIAL,
        REQUEST_MODEL,
        MODEL_RESULT_READY,
        TOOL_READY,
        TOOL_RESULT_READY,
        ANSWER_READY,
        PERSIST_COMPLETION,
        COMPLETED
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
            AgentRunContext context,
            RunPhase phase,
            LlmTurnResult modelTurnResult,
            LlmToolCall toolCall,
            ToolRegistry.RegisteredTool registeredTool,
            List<AgentEvent> pendingEvents) {

        private RunLoopState {
            pendingEvents = List.copyOf(pendingEvents);
        }

        private static RunLoopState initial(AgentRunContext context) {
            return new RunLoopState(
                    context,
                    RunPhase.INITIAL,
                    null,
                    null,
                    null,
                    List.of());
        }

        private RunLoopState transitionTo(RunPhase nextPhase) {
            return new RunLoopState(context, nextPhase, modelTurnResult, toolCall, registeredTool, List.of());
        }

        private RunLoopState withModelResult(RunPhase nextPhase, LlmTurnResult nextResult) {
            return new RunLoopState(context, nextPhase, nextResult, null, null, List.of());
        }

        private RunLoopState withEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(context, nextPhase, modelTurnResult, toolCall, registeredTool, nextEvents);
        }

        private RunLoopState withPendingTool(
                RunPhase nextPhase,
                LlmToolCall nextCall,
                ToolRegistry.RegisteredTool nextTool,
                List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, nextCall, nextTool, nextEvents);
        }

        private RunLoopState withToolOutcomeEvents(RunPhase nextPhase, List<AgentEvent> nextEvents) {
            return new RunLoopState(
                    context, nextPhase, modelTurnResult, toolCall, registeredTool, nextEvents);
        }
    }

}
