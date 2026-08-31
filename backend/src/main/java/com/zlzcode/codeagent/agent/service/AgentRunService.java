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

    private final OpenAiChatClient chatClient;
    private final WorkspaceRegistry workspaceRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentRunExceptionMapper exceptionMapper;
    private final LlmTurnStreamProcessor turnStreamProcessor;
    private final ConversationHistoryBuilder historyBuilder;
    private final SessionService sessionService;
    private final ToolDefinition workspaceTool;

    public AgentRunService(
            OpenAiChatClient chatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper exceptionMapper,
            LlmTurnStreamProcessor turnStreamProcessor,
            ConversationHistoryBuilder historyBuilder,
            SessionService sessionService,
            ToolDefinition workspaceTool) {
        this.chatClient = chatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.exceptionMapper = exceptionMapper;
        this.turnStreamProcessor = turnStreamProcessor;
        this.historyBuilder = historyBuilder;
        this.sessionService = sessionService;
        this.workspaceTool = workspaceTool;
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
                    .flatMapMany(run -> Flux.concat(
                            Flux.just(new AgentEvent.RunStarted(run.runId())),
                            runWithSession(request, run, startedAt)));
        }).onErrorResume(exceptionMapper::mapException);
    }

    private Flux<AgentEvent> runWithSession(
            AgentRunRequest request,
            SessionService.RunSession run,
            long startedAt) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"),
                        new AgentEvent.Status("正在分析")),
                Mono.fromCallable(() -> workspaceRegistry.resolve(run.workspaceId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(workspace -> {
                            List<LlmMessage> initialMessages = historyBuilder.build(
                                    AgentLlmContract.systemPrompt(workspaceTool.name()),
                                    run.completedHistory(),
                                    run.prompt());
                            AgentRunContext context = new AgentRunContext(
                                    new AgentRunContext.RunConfiguration(
                                            request.model(),
                                            request.reasoningEffort(),
                                            request.maxToolCalls(),
                                            request.openai()),
                                    run,
                                    workspace,
                                    initialMessages,
                                    startedAt);
                            return reactLoop(context);
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
    private Flux<AgentEvent> reactLoop(AgentRunContext context) {
        return Flux.just(ReActState.initial(context))
                .expandDeep(this::advance)
                .concatMapIterable(ReActState::events);
    }

    private Publisher<? extends ReActState> advance(ReActState state) {
        return switch (state.phase()) {
            case START -> Mono.just(state.next(Phase.REQUEST));
            case REQUEST -> requestModel(state.context())
                    .map(result -> state.withResult(Phase.DECIDE, result));
            case DECIDE -> decideState(state);
            case TOOL_STARTED -> executeToolState(state);
            case TOOL_FINISHED -> Mono.just(state.next(Phase.REQUEST));
            case ANSWER_TEXT -> Mono.just(state.next(Phase.PERSIST));
            case PERSIST -> persistState(state);
            case TERMINAL -> Mono.empty();
        };
    }

    private Mono<LlmTurnResult> requestModel(AgentRunContext context) {
        List<LlmRequest.ToolDeclaration> tools = context.canExecuteTool()
                ? List.of(new LlmRequest.ToolDeclaration(
                workspaceTool.name(),
                workspaceTool.description(),
                workspaceTool.parametersSchema()))
                : List.of();
        return turnStreamProcessor.collect(chatClient.chat(
                context.configuration().openai(),
                llmRequest(context, context.messages(), tools)));
    }

    private Publisher<? extends ReActState> decideState(ReActState state) {
        AgentRunContext context = state.context();
        LlmTurnResult result = state.result();
        context.recordModelTurn(result);
        TurnDecision decision = decide(context, result);
        if (decision instanceof TurnDecision.Fail fail) {
            return Mono.error(fail.error());
        }
        if (decision instanceof TurnDecision.FinishAnswer answer) {
            context.appendFinalAssistant(answer.content());
            return Mono.just(state.withEvents(
                    Phase.ANSWER_TEXT,
                    List.of(new AgentEvent.TextDelta(answer.content()))));
        }

        TurnDecision.ExecuteTool execute = (TurnDecision.ExecuteTool) decision;
        LlmToolCall call = execute.call();
        context.appendAssistantToolCalls(
                result.content(), result.hiddenReasoning(), List.of(call));
        ToolRegistry.RegisteredTool tool = toolRegistry.find(call.name());
        return Mono.just(state.withTool(
                Phase.TOOL_STARTED,
                call,
                tool,
                List.of(new AgentEvent.ToolStarted(
                        call.id(), tool.displayName(), null))));
    }

    private TurnDecision decide(AgentRunContext context, LlmTurnResult result) {
        if (result.hasToolCalls()) {
            if (result.toolCalls().size() != 1 || !context.canExecuteTool()) {
                return new TurnDecision.Fail(OpenAiIntegrationException.invalidToolCall());
            }
            return new TurnDecision.ExecuteTool(result.toolCalls().getFirst());
        }
        if (result.content() == null || result.content().isBlank()) {
            return new TurnDecision.Fail(OpenAiIntegrationException.noDisplayableResponse());
        }
        return new TurnDecision.FinishAnswer(result.content());
    }

    private Publisher<? extends ReActState> executeToolState(ReActState state) {
        return toolRegistry.execute(
                        state.tool(), state.context().workspace(), state.call().arguments())
                .map(outcome -> {
                    state.context().recordToolExecution(state.call(), outcome);
                    return state.withToolResultEvents(
                            Phase.TOOL_FINISHED,
                            List.of(
                                    new AgentEvent.ToolFinished(
                                            state.call().id(),
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
    private Publisher<? extends ReActState> persistState(ReActState state) {
        AgentRunContext context = state.context();
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        context.sessionRun(), state.result().content(), context.sessionToolHistory()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(state.withEvents(Phase.TERMINAL, List.of(completed(context))));
    }

    private AgentEvent.Completed completed(AgentRunContext context) {
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

    private LlmRequest llmRequest(
            AgentRunContext context,
            List<LlmMessage> messages,
            List<LlmRequest.ToolDeclaration> availableTools) {
        return new LlmRequest(
                context.configuration().model(),
                context.configuration().reasoningEffort(),
                messages,
                availableTools);
    }

    private enum Phase {
        START,
        REQUEST,
        DECIDE,
        TOOL_STARTED,
        TOOL_FINISHED,
        ANSWER_TEXT,
        PERSIST,
        TERMINAL
    }

    private sealed interface TurnDecision
            permits TurnDecision.ExecuteTool, TurnDecision.FinishAnswer, TurnDecision.Fail {

        record ExecuteTool(LlmToolCall call) implements TurnDecision {
        }

        record FinishAnswer(String content) implements TurnDecision {
        }

        record Fail(RuntimeException error) implements TurnDecision {
        }
    }

    private record ReActState(
            AgentRunContext context,
            Phase phase,
            LlmTurnResult result,
            LlmToolCall call,
            ToolRegistry.RegisteredTool tool,
            List<AgentEvent> events) {

        private ReActState {
            events = List.copyOf(events);
        }

        private static ReActState initial(AgentRunContext context) {
            return new ReActState(
                    context,
                    Phase.START,
                    null,
                    null,
                    null,
                    List.of());
        }

        private ReActState next(Phase nextPhase) {
            return new ReActState(context, nextPhase, result, call, tool, List.of());
        }

        private ReActState withResult(Phase nextPhase, LlmTurnResult nextResult) {
            return new ReActState(context, nextPhase, nextResult, null, null, List.of());
        }

        private ReActState withEvents(Phase nextPhase, List<AgentEvent> nextEvents) {
            return new ReActState(context, nextPhase, result, call, tool, nextEvents);
        }

        private ReActState withTool(
                Phase nextPhase,
                LlmToolCall nextCall,
                ToolRegistry.RegisteredTool nextTool,
                List<AgentEvent> nextEvents) {
            return new ReActState(
                    context, nextPhase, result, nextCall, nextTool, nextEvents);
        }

        private ReActState withToolResultEvents(Phase nextPhase, List<AgentEvent> nextEvents) {
            return new ReActState(
                    context, nextPhase, result, call, tool, nextEvents);
        }
    }

}
