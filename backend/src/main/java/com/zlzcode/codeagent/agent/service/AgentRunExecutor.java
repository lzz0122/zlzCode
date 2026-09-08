package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.context.ReActContext;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.store.RunStore;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
final class AgentRunExecutor {

    private final WorkspaceRegistry workspaceRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentRunExceptionMapper exceptionMapper;
    private final ConversationHistoryBuilder historyBuilder;
    private final SessionService sessionService;
    private final RunStore runStore;
    private final AgentRunScheduler scheduler;
    private final AgentReActLoop reActLoop;

    @Value("${codeagent.run.timeout:PT5M}")
    private Duration runTimeout;

    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    AgentRunExecutor(
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper exceptionMapper,
            ConversationHistoryBuilder historyBuilder,
            SessionService sessionService,
            RunStore runStore,
            AgentRunScheduler scheduler,
            AgentReActLoop reActLoop) {
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.exceptionMapper = exceptionMapper;
        this.historyBuilder = historyBuilder;
        this.sessionService = sessionService;
        this.runStore = runStore;
        this.scheduler = scheduler;
        this.reActLoop = reActLoop;
    }

    /*
     * 背景：Run 创建成功后可能先在队列中等待，SSE 客户端却已经拿到 runId 并立即订阅。
     * 设计意图：先登记唯一的 ActiveRun 事件通道，再交给 Scheduler 排队；执行器统一处理排队失败。
     * 关键约束：不能等任务真正开始后再创建事件通道，否则排队窗口会被错误地当成空流。
     */
    void start(RunExecution execution) {
        ActiveRun active = new ActiveRun(execution.runId());
        if (activeRuns.putIfAbsent(execution.runId(), active) != null) {
            throw new IllegalStateException("Run is already active: " + execution.runId());
        }
        try {
            CompletableFuture<Void> scheduled = scheduler.schedule(
                    execution.sessionId(),
                    () -> execute(execution, active).toFuture());
            scheduled.whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(active, error);
                    finish(active);
                }
            });
        } catch (RuntimeException exception) {
            fail(active, exception);
            finish(active);
        }
    }

    /*
     * 背景：后台 Run 的生命周期不能由 SSE 订阅拥有，客户端断开时模型和工具仍需继续执行并收口结果。
     * 设计意图：执行开始时刷新 Session 快照，运行 ReAct 流并施加整体超时，再统一投影成功或失败状态。
     * 关键约束：异常必须进入 fail 并终止事件流；不能让订阅取消或单次调用超时留下永久 RUNNING。
     */
    private Mono<Void> execute(RunExecution execution, ActiveRun active) {
        return Mono.defer(() -> {
                    SessionRunSnapshot sessionRun = sessionService.refreshRun(
                            execution.sessionId(), execution.runId(), execution.prompt());
                    active.emit(new AgentEvent.RunStarted(execution.runId()));
                    long startedAt = System.nanoTime();
                    return Mono.fromCallable(() -> workspaceRegistry.resolve(sessionRun.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(workspace -> {
                                List<com.zlzcode.codeagent.agent.model.LlmMessage> initialMessages =
                                        historyBuilder.build(
                                                AgentLlmContract.systemPrompt(),
                                                sessionRun.completedHistory(),
                                                sessionRun.prompt());
                                ReActContext context = new ReActContext(
                                        execution, workspace, initialMessages, startedAt);
                                return reActLoop.run(context)
                                        .doOnNext(active::emit)
                                        .then(Mono.defer(() -> complete(
                                                execution, sessionRun, context, active)));
                            });
                })
                .timeout(runTimeout)
                .doOnError(error -> fail(active, error))
                .onErrorResume(error -> Mono.empty())
                .doOnSuccess(ignored -> finish(active));
    }

    private Mono<Void> complete(
            RunExecution execution,
            SessionRunSnapshot sessionRun,
            ReActContext context,
            ActiveRun active) {
        ReActContext.Completion completion = context.completion();
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        sessionRun,
                        completion.finalAnswer(),
                        completion.sessionToolHistory()))
                .doOnSuccess(ignored -> runStore.succeed(
                        execution.runId(),
                        completion.finalAnswer(),
                        completion.sessionToolHistory(),
                        completion.metrics()))
                .then(Mono.fromRunnable(() -> active.emit(new AgentEvent.Completed(
                        completion.metrics(), completion.toolHistory()))));
    }

    Flux<AgentEvent> events(String sessionId, String runId) {
        RunRecord record = runStore.read(sessionId, runId);
        ActiveRun active = activeRuns.get(runId);
        if (active != null && record.status() == RunStatus.RUNNING) {
            return active.events.asFlux();
        }
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
                    record.errorMessage(),
                    record.errorCode(),
                    Boolean.TRUE.equals(record.errorRetryable())));
        }
        return Flux.empty();
    }

    /*
     * 背景：SSE 已经开始输出后，HTTP 异常处理器无法修改响应，只能发送安全的终态错误事件。
     * 设计意图：复用统一异常映射，同时先持久化 FAILED 再结束旁路，让断线后的查询仍能得到结论。
     * 关键约束：错误消息不得泄露堆栈、密钥或本机路径；失败收口后不能再发送 Completed。
     */
    private void fail(ActiveRun active, Throwable error) {
        AgentEvent.Error event = exceptionMapper.mapException(error)
                .onErrorReturn(new AgentEvent.Error("Agent 运行失败", "AGENT_RUN_FAILED", false))
                .block(Duration.ofSeconds(1));
        if (event == null) event = new AgentEvent.Error("Agent 运行失败", "AGENT_RUN_FAILED", false);
        try {
            runStore.fail(active.runId, event.code(), event.message(), event.retryable());
        } catch (RuntimeException ignored) {
        }
        active.emit(event);
    }

    private void finish(ActiveRun active) {
        active.events.tryEmitComplete();
        activeRuns.remove(active.runId, active);
    }

    private static final class ActiveRun {
        private final String runId;
        private final Sinks.Many<AgentEvent> events =
                Sinks.many().multicast().onBackpressureBuffer();

        private ActiveRun(String runId) {
            this.runId = runId;
        }

        private void emit(AgentEvent event) {
            events.tryEmitNext(event);
        }
    }
}
