package com.zlzcode.codeagent.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.context.ReActContext;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.store.RunStore;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import com.zlzcode.codeagent.tool.service.RunFileObservationService;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
final class AgentRunExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunExecutor.class);
    private static final AgentEvent.Error GENERIC_FAILURE =
            new AgentEvent.Error("Agent 运行失败", "AGENT_RUN_FAILED", false);
    private static final AgentEvent.Error ORPHANED_RUN_FAILURE =
            new AgentEvent.Error("Agent 运行状态已丢失，请重新发起任务", "AGENT_RUN_UNAVAILABLE", true);

    private final WorkspaceRegistry workspaceRegistry;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ApprovalService approvalService;
    private final AgentRunExceptionMapper exceptionMapper;
    private final ConversationHistoryBuilder historyBuilder;
    private final SessionService sessionService;
    private final RunStore runStore;
    private final AgentRunScheduler scheduler;
    private final AgentReActLoop reActLoop;
    private final RunFileObservationService observationService;

    @Value("${codeagent.run.timeout:PT5M}")
    private Duration runTimeout;

    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    AgentRunExecutor(
            WorkspaceRegistry workspaceRegistry,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            ApprovalService approvalService,
            AgentRunExceptionMapper exceptionMapper,
            ConversationHistoryBuilder historyBuilder,
            SessionService sessionService,
            RunStore runStore,
            AgentRunScheduler scheduler,
            AgentReActLoop reActLoop,
            RunFileObservationService observationService) {
        this.workspaceRegistry = workspaceRegistry;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
        this.exceptionMapper = exceptionMapper;
        this.historyBuilder = historyBuilder;
        this.sessionService = sessionService;
        this.runStore = runStore;
        this.scheduler = scheduler;
        this.reActLoop = reActLoop;
        this.observationService = observationService;
    }

    /*
     * 背景：Run 创建成功后可能先在队列中等待，SSE 客户端却已经拿到 runId 并立即订阅。
     * 设计意图：先登记唯一的 ActiveRun 事件通道，再交给 Scheduler 排队；执行器统一处理排队失败。
     * 关键约束：不能等任务真正开始后再创建事件通道，否则排队窗口会被错误地当成空流。
     */
    void start(RunExecution execution) {
        ActiveRun active = new ActiveRun(execution.sessionId(), execution.runId());
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
                    LOGGER.info("Agent Run started: runId={}, sessionId={}",
                            execution.runId(), execution.sessionId());
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
                                ToolApprovalCoordinator approvalCoordinator =
                                        (toolContext, plan) -> awaitApprovalOutcome(
                                                execution, active, toolContext, plan);
                                return reActLoop.run(context, approvalCoordinator)
                                        .doOnNext(active::emit)
                                        .then(Mono.defer(() -> complete(
                                                execution, sessionRun, context, active)));
                            });
                })
                .timeout(runTimeout)
                .doOnError(error -> {
                    cleanupApproval(active);
                    fail(active, error);
                })
                .onErrorResume(error -> Mono.empty())
                .doOnSuccess(ignored -> finish(active));
    }

    /*
     * 背景：修改工具需要在原 Run 内等待决定，并在批准后提交当时持久化的固定计划。
     * 设计意图：Executor 为每个 Run 绑定协调器，串行公开等待状态、等待决定和可选提交，不建立共享的“当前 Run”服务。
     * 关键约束：必须先持久化请求再公开 approvalId，批准时只能提交 ApprovalRecord 中的原计划；颠倒顺序或重建计划会导致查询不可见或批准内容与实际操作不一致。
     */
    private Mono<ToolOutcome> awaitApprovalOutcome(
            RunExecution execution,
            ActiveRun active,
            ToolExecutionContext context,
            MutationPlan plan) {
        return Mono.fromCallable(() -> {
                    ApprovalRecord request = approvalService.createRequest(
                            execution.sessionId(), context, plan);
                    active.beginApproval(request);
                    runStore.enterWaitingApproval(
                            context.runId(), request.approvalId(), context.toolCallId());
                    return request;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(request -> approvalService.awaitDecision(
                        execution.sessionId(), context.runId(), request.approvalId()))
                .flatMap(approval -> resolveApproval(
                        execution, active, context, plan, approval));
    }

    private Mono<ToolOutcome> resolveApproval(
            RunExecution execution,
            ActiveRun active,
            ToolExecutionContext context,
            MutationPlan requestedPlan,
            ApprovalRecord approval) {
        validateApproval(execution, context, requestedPlan, approval);
        if (approval.status() == ApprovalRecord.Status.EXPIRED) {
            return Mono.error(ApprovalException.expired());
        }
        if (approval.status() == ApprovalRecord.Status.INVALIDATED
                || approval.status() == ApprovalRecord.Status.PENDING) {
            return Mono.error(ApprovalException.stateConflict());
        }
        if (approval.decision() == ApprovalRecord.Decision.REJECT) {
            runStore.leaveWaitingApproval(context.runId(), approval.approvalId());
            active.clearApproval(approval.approvalId());
            return Mono.just(ToolOutcome.failure(
                    objectMapper,
                    "TOOL_APPROVAL_REJECTED",
                    "用户拒绝了工具操作",
                    "The requested tool operation was rejected by the user."));
        }
        if (approval.decision() != ApprovalRecord.Decision.APPROVE) {
            return Mono.error(ApprovalException.stateConflict());
        }

        runStore.leaveWaitingApproval(context.runId(), approval.approvalId());
        ApprovalRecord committing = approvalService.startCommit(
                execution.sessionId(), context.runId(), approval.approvalId());
        active.markCommitStarted(approval.approvalId());
        return toolRegistry.commit(context, committing.plan())
                .map(outcome -> {
                    ApprovalRecord completed = approvalService.completeCommit(
                            execution.sessionId(), context.runId(), approval.approvalId(), outcome);
                    active.markOutcomeRecorded(approval.approvalId());
                    active.clearApproval(approval.approvalId());
                    return completed.outcome();
                });
    }

    private void validateApproval(
            RunExecution execution,
            ToolExecutionContext context,
            MutationPlan requestedPlan,
            ApprovalRecord approval) {
        if (!execution.sessionId().equals(approval.sessionId())
                || !context.runId().equals(approval.runId())
                || !context.toolCallId().equals(approval.toolCallId())
                || !context.workspace().id().equals(approval.workspaceId())
                || !requestedPlan.equals(approval.plan())) {
            throw ApprovalException.stateConflict();
        }
    }

    /*
     * 背景：整段 Run 超时或协调异常可能发生在审批等待中，也可能发生在修改已开始但结果尚未落盘时。
     * 设计意图：只记录最小提交进度，失败时尝试一次 invalidate 或 UNKNOWN 收口，然后复用现有 Run.fail。
     * 关键约束：取得提交资格后不能再标记为“未执行”或自动重试；否则实际已发生的文件修改可能被重复执行。
     */
    private void cleanupApproval(ActiveRun active) {
        ApprovalProgress progress = active.approvalProgress();
        if (progress == null || !progress.cleanupAttempted.compareAndSet(false, true)) {
            return;
        }
        try {
            if (progress.commitStarted && !progress.outcomeRecorded) {
                approvalService.markCommitUnknown(
                        progress.sessionId,
                        progress.runId,
                        progress.approvalId,
                        "Run 终止时提交结果无法确认");
            } else if (!progress.commitStarted) {
                approvalService.invalidate(
                        progress.sessionId,
                        progress.runId,
                        progress.approvalId,
                        "Run 在提交前终止");
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Agent approval cleanup failed: runId={}, sessionId={}, approvalId={}",
                    progress.runId, progress.sessionId, progress.approvalId, exception);
        }
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
                .then(Mono.<Void>fromRunnable(() -> active.emitTerminal(new AgentEvent.Completed(
                        completion.metrics(), completion.toolHistory()))))
                .doOnSuccess(ignored -> LOGGER.info(
                        "Agent Run succeeded: runId={}, sessionId={}",
                        execution.runId(), execution.sessionId()));
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
        if (active == null && (record.status() == RunStatus.RUNNING
                || record.status() == RunStatus.WAITING_APPROVAL)) {
            LOGGER.error(
                    "Agent Run has no active executor: runId={}, sessionId={}, status={}",
                    record.runId(), record.sessionId(), record.status());
            return Flux.just(ORPHANED_RUN_FAILURE);
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
                .onErrorReturn(GENERIC_FAILURE)
                .block(Duration.ofSeconds(1));
        if (event == null) event = GENERIC_FAILURE;
        active.rememberFailure(event);
        LOGGER.error(
                "Agent Run failed: runId={}, sessionId={}, code={}",
                active.runId, active.sessionId, event.code(), error);
        try {
            runStore.fail(active.runId, event.code(), event.message(), event.retryable());
        } catch (RuntimeException persistenceError) {
            LOGGER.error(
                    "Agent Run failure persistence failed: runId={}, sessionId={}, code={}",
                    active.runId, active.sessionId, event.code(), persistenceError);
        }
        active.emitTerminal(event);
    }

    /*
     * 背景：执行流可能已经结束，但失败状态持久化本身也可能异常，旧实现会直接删除 ActiveRun 并留下无法解释的 RUNNING 记录。
     * 设计意图：在释放事件通道前检查持久化终态，发现未收口时只补做一次失败写入并记录诊断日志。
     * 关键约束：这里不能循环重试或恢复业务执行；否则持久化故障会阻塞调度器，修改工具也可能被错误地再次执行。
     */
    private void finish(ActiveRun active) {
        try {
            RunRecord record = runStore.read(active.sessionId, active.runId);
            if (record.status() != RunStatus.SUCCEEDED && record.status() != RunStatus.FAILED) {
                AgentEvent.Error event = active.failureEvent == null
                        ? GENERIC_FAILURE
                        : active.failureEvent;
                LOGGER.error(
                        "Agent Run reached executor finish without terminal status: "
                                + "runId={}, sessionId={}, status={}, code={}",
                        active.runId, active.sessionId, record.status(), event.code());
                try {
                    runStore.fail(active.runId, event.code(), event.message(), event.retryable());
                } catch (RuntimeException persistenceError) {
                    LOGGER.error(
                            "Agent Run terminal repair failed: runId={}, sessionId={}, code={}",
                            active.runId, active.sessionId, event.code(), persistenceError);
                }
                active.emitTerminal(event);
            }
        } catch (RuntimeException inspectionError) {
            LOGGER.error(
                    "Agent Run terminal inspection failed: runId={}, sessionId={}",
                    active.runId, active.sessionId, inspectionError);
            active.emitTerminal(GENERIC_FAILURE);
        }
        /*
         * 背景：观察文件版本只授权当前 Run 后续的覆盖或编辑，Run 终结后已没有合法消费者。
         * 设计意图：由拥有 Run 生命周期的 Executor 统一清理，而不是让单个工具猜测最后一次调用。
         * 关键约束：不能保留到 Session 或后续 Run；否则旧读取可能错误授权新的文件修改。
         */
        observationService.clear(active.runId);
        active.events.tryEmitComplete();
        activeRuns.remove(active.runId, active);
    }

    private static final class ActiveRun {
        private final String sessionId;
        private final String runId;
        private final Sinks.Many<AgentEvent> events =
                Sinks.many().multicast().onBackpressureBuffer();
        private final AtomicBoolean terminalEventEmitted = new AtomicBoolean();
        private volatile ApprovalProgress approvalProgress;
        private volatile AgentEvent.Error failureEvent;

        private ActiveRun(String sessionId, String runId) {
            this.sessionId = sessionId;
            this.runId = runId;
        }

        private void emit(AgentEvent event) {
            Sinks.EmitResult result = events.tryEmitNext(event);
            if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                LOGGER.warn(
                        "Agent event delivery failed: runId={}, sessionId={}, type={}, result={}",
                        runId, sessionId, event.type(), result);
            }
        }

        private void emitTerminal(AgentEvent event) {
            if (terminalEventEmitted.compareAndSet(false, true)) {
                emit(event);
            }
        }

        private void rememberFailure(AgentEvent.Error event) {
            failureEvent = event;
        }

        private synchronized void beginApproval(ApprovalRecord approval) {
            if (approvalProgress != null) {
                throw new IllegalStateException("Run already has an active approval");
            }
            approvalProgress = new ApprovalProgress(
                    approval.sessionId(), approval.runId(), approval.approvalId());
        }

        private ApprovalProgress approvalProgress() {
            return approvalProgress;
        }

        private synchronized void markCommitStarted(String approvalId) {
            requireApproval(approvalId).commitStarted = true;
        }

        private synchronized void markOutcomeRecorded(String approvalId) {
            requireApproval(approvalId).outcomeRecorded = true;
        }

        private synchronized void clearApproval(String approvalId) {
            requireApproval(approvalId);
            approvalProgress = null;
        }

        private ApprovalProgress requireApproval(String approvalId) {
            ApprovalProgress current = approvalProgress;
            if (current == null || !current.approvalId.equals(approvalId)) {
                throw new IllegalStateException("Run approval identity changed unexpectedly");
            }
            return current;
        }
    }

    private static final class ApprovalProgress {
        private final String sessionId;
        private final String runId;
        private final String approvalId;
        private final AtomicBoolean cleanupAttempted = new AtomicBoolean();
        private volatile boolean commitStarted;
        private volatile boolean outcomeRecorded;

        private ApprovalProgress(String sessionId, String runId, String approvalId) {
            this.sessionId = sessionId;
            this.runId = runId;
            this.approvalId = approvalId;
        }
    }
}
