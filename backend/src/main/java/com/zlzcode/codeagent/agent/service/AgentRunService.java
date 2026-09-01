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
import com.zlzcode.codeagent.agent.model.AgentRunSnapshot;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.agent.store.RunStore;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final RunStore runStore;

    @Value("${codeagent.run.max-concurrency:4}")
    private int maxConcurrency;

    @Value("${codeagent.run.queue-capacity:100}")
    private int queueCapacity;

    @Value("${codeagent.run.timeout:PT5M}")
    private Duration runTimeout;

    private final Map<String, ManagedRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> sessionTails = new ConcurrentHashMap<>();
    private ExecutorService runExecutor;

    public AgentRunService(
            OpenAiChatClient openAiChatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper agentRunExceptionMapper,
            LlmTurnStreamProcessor turnStreamProcessor,
            ConversationHistoryBuilder conversationHistoryBuilder,
            SessionService sessionService,
            RunStore runStore) {
        this.openAiChatClient = openAiChatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.agentRunExceptionMapper = agentRunExceptionMapper;
        this.turnStreamProcessor = turnStreamProcessor;
        this.conversationHistoryBuilder = conversationHistoryBuilder;
        this.sessionService = sessionService;
        this.runStore = runStore;
    }

    @PostConstruct
    void startExecutor() {
        runExecutor = new ThreadPoolExecutor(
                maxConcurrency,
                maxConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void stopExecutor() {
        if (runExecutor != null) runExecutor.shutdownNow();
    }

    /*
     * 背景：HTTP 重试可能在第一次提交已落盘后再次到达，必须避免重复创建 Turn 和重复执行工具。
     * 设计意图：先按 Session 作用域的幂等键复用或拒绝冲突请求，再持久化 RUNNING 并提交后台任务。
     * 关键约束：RunStore 成功记录 RUNNING 后才能入队；不能把幂等判断放到异步任务中，否则响应与执行会脱节。
     */
    public synchronized RunResponse submit(AgentRunRequest request) {
        String key = request.idempotencyKey().trim();
        String fingerprint = fingerprint(request);
        Optional<RunRecord> existing = runStore.findByIdempotency(request.sessionId(), key);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw com.zlzcode.codeagent.agent.exception.RunException.idempotencyConflict();
            }
            return RunResponse.from(existing.get());
        }

        String runId = "run-" + UUID.randomUUID();
        SessionService.RunSession sessionRun = sessionService.beginRun(
                request.sessionId(), runId, request.prompt());
        RunRecord record = runStore.create(new AgentRunSnapshot(
                runId, request.sessionId(), key, fingerprint, java.time.Instant.now()));
        ManagedRun managed = new ManagedRun(runId);
        activeRuns.put(runId, managed);
        enqueue(request, sessionRun, managed);
        return RunResponse.from(record);
    }

    /*
     * 背景：客户端断线后仍可能重新连接，但中间事件不需要历史回放，最终状态必须可查询。
     * 设计意图：运行中只暴露内存实时旁路，已结束 Run 从持久化结果构造终态事件。
     * 关键约束：必须同时校验 sessionId 和 runId；不能仅凭不可猜的 Run ID 授权访问。
     */
    public Flux<AgentEvent> events(String sessionId, String runId) {
        RunRecord record = runStore.read(sessionId, runId);
        ManagedRun active = activeRuns.get(runId);
        if (active != null && record.status() == RunStatus.RUNNING) return active.sink.asFlux();
        if (record.status() == RunStatus.SUCCEEDED) {
            return Flux.just(
                    new AgentEvent.TextDelta(record.finalAnswer()),
                    new AgentEvent.Completed(record.metrics(), record.toolHistory().stream()
                            .map(tool -> new AgentEvent.ToolHistory(
                                    tool.name(), tool.arguments(), tool.result()))
                            .toList()));
        }
        if (record.status() == RunStatus.FAILED) {
            return Flux.just(new AgentEvent.Error(
                    record.errorMessage(), record.errorCode(), Boolean.TRUE.equals(record.errorRetryable())));
        }
        return Flux.empty();
    }

    public RunResponse read(String sessionId, String runId) {
        return RunResponse.from(runStore.read(sessionId, runId));
    }

    /*
     * 背景：同一 Session 的多个 Run 共享会话历史，完成顺序必须与提交顺序一致。
     * 设计意图：用按 Session 链接的 Future 串行化任务，同时让不同 Session 使用有界执行器并行。
     * 关键约束：链必须等待完整 Run 终态，不能在 subscribe 后立即返回，否则后续 Run 会并发读取旧历史。
     */
    private void enqueue(
            AgentRunRequest request,
            SessionService.RunSession sessionRun,
            ManagedRun managed) {
        CompletableFuture<Void> previous = sessionTails.getOrDefault(
                request.sessionId(), CompletableFuture.completedFuture(null));
            CompletableFuture<Void> current;
        try {
            current = previous.handle((ignored, error) -> null)
                    .thenComposeAsync(ignored -> execute(request, sessionRun, managed).toFuture(), runExecutor);
        } catch (RuntimeException exception) {
            fail(managed, exception);
            return;
        }
        sessionTails.put(request.sessionId(), current);
        current.whenComplete((ignored, error) -> sessionTails.remove(request.sessionId(), current));
    }

    /*
     * 背景：后台 Run 的生命周期不能由 SSE 订阅拥有，客户端断开时模型和工具仍需继续执行并收口结果。
     * 设计意图：在独立执行器中刷新会话历史、运行 ReAct 流并施加整体超时，再统一投影成功或失败状态。
     * 关键约束：异常必须进入 fail 并终止事件流；不能让订阅取消或单次调用超时留下永久 RUNNING。
     */
    private Mono<Void> execute(
            AgentRunRequest request,
            SessionService.RunSession sessionRun,
            ManagedRun managed) {
        return Mono.defer(() -> {
                    SessionService.RunSession effectiveSessionRun = sessionService.refreshRun(sessionRun);
                    managed.sink.tryEmitNext(new AgentEvent.RunStarted(effectiveSessionRun.runId()));
                    long startedAt = System.nanoTime();
                    return Mono.fromCallable(() -> workspaceRegistry.resolve(effectiveSessionRun.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(workspace -> {
                                List<LlmMessage> initialMessages = conversationHistoryBuilder.build(
                                        AgentLlmContract.systemPrompt(toolRegistry.promptToolName()),
                                        effectiveSessionRun.completedHistory(), effectiveSessionRun.prompt());
                                AgentRunContext context = new AgentRunContext(
                                        new AgentRunContext.RunConfiguration(
                                                request.model(), request.reasoningEffort(), request.maxToolCalls(), request.openai()),
                                        effectiveSessionRun, workspace, initialMessages, startedAt);
                                return initializeRunExecution(context);
                            })
                            .timeout(runTimeout)
                            .doOnNext(event -> managed.sink.tryEmitNext(event))
                            .then();
                })
                .doOnError(error -> fail(managed, error))
                .onErrorResume(error -> Mono.empty())
                .doOnSuccess(ignored -> finish(managed));
    }

    private Flux<AgentEvent> initializeRunExecution(AgentRunContext context) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"), new AgentEvent.Status("正在分析")),
                processRunToCompletion(context));
    }

    private void finish(ManagedRun managed) {
        managed.sink.tryEmitComplete();
        activeRuns.remove(managed.runId);
    }

    /*
     * 背景：SSE 已经开始输出后，HTTP 异常处理器无法修改响应，只能发送安全的终态错误事件。
     * 设计意图：复用统一异常映射，同时先持久化 FAILED 再结束旁路，让断线后的查询仍能得到结论。
     * 关键约束：错误消息不得泄露堆栈、密钥或本机路径；失败收口后不能再发送 Completed。
     */
    private void fail(ManagedRun managed, Throwable error) {
        AgentEvent.Error event = agentRunExceptionMapper.mapException(error)
                .onErrorReturn(new AgentEvent.Error("Agent 运行失败", "AGENT_RUN_FAILED", false))
                .block(Duration.ofSeconds(1));
        if (event == null) event = new AgentEvent.Error("Agent 运行失败", "AGENT_RUN_FAILED", false);
        try {
            runStore.fail(managed.runId, event.code(), event.message(), event.retryable());
        } catch (RuntimeException ignored) {
        }
        managed.sink.tryEmitNext(event);
        finish(managed);
    }

    private String fingerprint(AgentRunRequest request) {
        String value = String.join("\u0000", request.sessionId(), request.prompt(), request.model(),
                request.reasoningEffort() == null ? "" : request.reasoningEffort(),
                String.valueOf(request.maxToolCalls()), request.openai().baseUrl(), request.openai().apiKey());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static final class ManagedRun {
        private final String runId;
        private final Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        private ManagedRun(String runId) {
            this.runId = runId;
        }
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

    /*
     * 背景：ReAct 每一步都可能触发异步模型或工具操作，分散的递归流难以审查合法终态。
     * 设计意图：把每个 phase 的唯一后继集中在一个推进器中，事件只从状态快照投影出去。
     * 关键约束：只能沿枚举定义的方向前进，COMPLETED 必须是唯一无后继状态，不能绕过持久化阶段。
     */
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
                ? toolRegistry.modelToolDeclarations()
                : List.of();
        return turnStreamProcessor.collect(openAiChatClient.chat(
                context.configuration().openai(),
                buildLlmRequest(context, context.messages(), tools)));
    }

    /*
     * 背景：模型结果可能是最终文本，也可能是需要本机执行的工具调用，二者对历史消息和事件顺序要求不同。
     * 设计意图：先记录模型 Turn，再把结果分类为回答、工具或非法响应，避免在多个分支重复计数。
     * 关键约束：工具调用必须先写入 AssistantToolCalls 并发出 ToolStarted，最终文本不能带着未完成工具调用写入。
     */
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
        return Mono.just(state.withPendingTool(
                RunPhase.TOOL_READY,
                call,
                List.of(new AgentEvent.ToolStarted(
                        call.id(), toolRegistry.displayName(call.name()), null))));
    }

    /*
     * 背景：工具额度和工具注册状态是模型进入本机执行边界前的最后校验。
     * 设计意图：已注册工具的业务失败交给下一轮模型修正，协议非法或额度耗尽则终止 Run。
     * 关键约束：每轮最多接受一个工具调用；不能因为工具不可用而绕过额度校验或执行校验。
     */
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

    /*
     * 背景：前端收到 completed 后会把本轮视为可进入下一轮的稳定历史，不能先于 Session 文件落盘。
     * 设计意图：在 Agent 编排边界等待完整 Turn 原子保存，再创建终态事件；不让流处理器直接宣布成功。
     * 关键约束：持久化失败必须转成 Error 并保留 incomplete Turn，绝不能继续发送 completed。
     */
    private Publisher<? extends RunLoopState> persistRunCompletion(RunLoopState state) {
        AgentRunContext context = state.context();
        AgentEvent.Completed completed = buildCompletedEvent(context);
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        context.sessionRun(), state.modelTurnResult().content(), context.sessionToolHistory()))
                .doOnSuccess(ignored -> runStore.succeed(
                        context.sessionRun().runId(), state.modelTurnResult().content(),
                        context.sessionToolHistory(), completed.metrics()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(state.withEvents(RunPhase.COMPLETED, List.of(completed)));
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
            List<AgentEvent> pendingEvents) {

        /*
         * 背景：状态机不同阶段携带的数据不同，Java record 需要用可空字段表达尚未产生的结果。
         * 设计意图：通过各个工厂方法集中构造状态，保持 phase 与 payload 的更新同步。
         * 关键约束：新增 phase 时必须同步定义其必需字段和唯一转移，不能直接读取其他阶段的可空数据。
         */
        private RunLoopState {
            pendingEvents = List.copyOf(pendingEvents);
        }

        private static RunLoopState initial(AgentRunContext context) {
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
