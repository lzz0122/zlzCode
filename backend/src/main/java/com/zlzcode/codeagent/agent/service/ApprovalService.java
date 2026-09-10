package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.ApprovalResponse;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.agent.store.ApprovalStore;
import com.zlzcode.codeagent.agent.store.RunStore;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public final class ApprovalService {

    private static final String APPROVAL_ID_PREFIX = "approval-";

    private final ApprovalStore approvalStore;
    private final RunStore runStore;
    private final ToolProperties toolProperties;
    private final Clock clock;
    private final ConcurrentMap<String, Sinks.One<ApprovalRecord>> decisionSignals =
            new ConcurrentHashMap<>();

    @Autowired
    public ApprovalService(
            ApprovalStore approvalStore,
            RunStore runStore,
            ToolProperties toolProperties) {
        this(approvalStore, runStore, toolProperties, Clock.systemUTC());
    }

    ApprovalService(
            ApprovalStore approvalStore,
            RunStore runStore,
            ToolProperties toolProperties,
            Clock clock) {
        this.approvalStore = Objects.requireNonNull(approvalStore, "Approval store cannot be null");
        this.runStore = Objects.requireNonNull(runStore, "Run store cannot be null");
        this.toolProperties = Objects.requireNonNull(toolProperties, "Tool properties cannot be null");
        this.clock = Objects.requireNonNull(clock, "Approval clock cannot be null");
    }

    public ApprovalRecord createRequest(
            String sessionId,
            ToolExecutionContext context,
            MutationPlan plan) {
        Objects.requireNonNull(context, "Tool execution context cannot be null");
        Objects.requireNonNull(plan, "Mutation plan cannot be null");

        RunRecord run = runStore.read(sessionId, context.runId());
        if (run.status() != RunStatus.RUNNING
                || !context.runId().equals(plan.runId())
                || !context.toolCallId().equals(plan.toolCallId())) {
            throw ApprovalException.stateConflict();
        }

        Instant now = clock.instant();
        String approvalId = APPROVAL_ID_PREFIX + UUID.randomUUID();
        ApprovalRecord record = new ApprovalRecord(
                approvalId,
                sessionId,
                context.runId(),
                context.toolCallId(),
                context.workspace().id(),
                plan,
                ApprovalRecord.Status.PENDING,
                null,
                ApprovalRecord.CommitState.NOT_STARTED,
                null,
                now,
                now.plus(toolProperties.mutation().approvalTimeout()),
                null,
                now,
                null);
        Sinks.One<ApprovalRecord> signal = Sinks.one();

        /*
         * 背景：Runtime 会在审批请求持久化后才公开 approvalId，但决定可能早于 awaitDecision 订阅。
         * 设计意图：创建请求时同时登记一次性信号，并以持久化记录作为最终事实来源，而不是依赖订阅先到达。
         * 关键约束：持久化失败必须移除信号；否则内存会出现不存在的审批，后续通知可能唤醒错误流程。
         */
        if (decisionSignals.putIfAbsent(approvalId, signal) != null) {
            throw ApprovalException.stateConflict();
        }
        try {
            return approvalStore.create(record);
        } catch (RuntimeException exception) {
            decisionSignals.remove(approvalId, signal);
            throw exception;
        }
    }

    public ApprovalResponse query(String sessionId, String runId, String approvalId) {
        ApprovalRecord approval = refreshExpiration(
                approvalStore.read(sessionId, runId, approvalId));
        return response(approval);
    }

    public ApprovalResponse decide(
            String sessionId,
            String runId,
            String approvalId,
            ApprovalRecord.Decision decision) {
        Objects.requireNonNull(decision, "Approval decision cannot be null");
        ApprovalRecord current = refreshExpiration(
                approvalStore.read(sessionId, runId, approvalId));
        if (current.status() != ApprovalRecord.Status.PENDING) {
            return response(current);
        }

        RunRecord run = runStore.read(sessionId, runId);
        if (run.status() != RunStatus.WAITING_APPROVAL
                || !approvalId.equals(run.pendingApprovalId())
                || !current.toolCallId().equals(run.pendingToolCallId())) {
            throw ApprovalException.stateConflict();
        }

        /*
         * 背景：HTTP 重试、期限回调和原 Run 的等待流程可能同时处理同一审批请求。
         * 设计意图：先让 Store 在锁内确定唯一终态，再把已落盘结果作为一次性通知发给 Runtime。
         * 关键约束：不能先发信号再保存决定；否则 Run 可能开始提交一个重启后不存在或已被期限覆盖的批准。
         */
        ApprovalRecord resolved = approvalStore.decide(
                sessionId, runId, approvalId, decision, clock.instant());
        emitResolution(resolved);
        return response(resolved);
    }

    public Mono<ApprovalRecord> awaitDecision(
            String sessionId,
            String runId,
            String approvalId) {
        return Mono.defer(() -> {
                    ApprovalRecord current = refreshExpiration(
                            approvalStore.read(sessionId, runId, approvalId));
                    if (current.status() != ApprovalRecord.Status.PENDING) {
                        decisionSignals.remove(approvalId);
                        return Mono.just(current);
                    }

                    Sinks.One<ApprovalRecord> signal = decisionSignals.get(approvalId);
                    if (signal == null) {
                        return Mono.error(ApprovalException.stateConflict());
                    }
                    Duration remaining = Duration.between(clock.instant(), current.expiresAt());
                    if (remaining.isZero() || remaining.isNegative()) {
                        return Mono.just(expire(sessionId, runId, approvalId));
                    }

                    /*
                     * 背景：等待用户决定可能持续数分钟，期间不能占用本地 I/O 线程，也不能靠轮询审批文件推进。
                     * 设计意图：竞争一次性决定信号与期限定时器，终态到达后再切到 boundedElastic 交给后续协调流程。
                     * 关键约束：每次订阅都必须先读持久化状态；否则先决定后订阅会丢通知并让 Run 永久等待。
                     */
                    Mono<ApprovalRecord> expiration = Mono.delay(remaining)
                            .publishOn(Schedulers.boundedElastic())
                            .map(ignored -> expire(sessionId, runId, approvalId));
                    return Mono.firstWithSignal(signal.asMono(), expiration)
                            .doOnNext(ignored -> decisionSignals.remove(approvalId, signal));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.boundedElastic());
    }

    public ApprovalRecord startCommit(String sessionId, String runId, String approvalId) {
        return approvalStore.startCommit(sessionId, runId, approvalId);
    }

    public ApprovalRecord completeCommit(
            String sessionId,
            String runId,
            String approvalId,
            ToolOutcome outcome) {
        return approvalStore.completeCommit(sessionId, runId, approvalId, outcome);
    }

    public ApprovalRecord markCommitUnknown(
            String sessionId,
            String runId,
            String approvalId,
            String reason) {
        return approvalStore.markCommitUnknown(sessionId, runId, approvalId, reason);
    }

    public ApprovalRecord invalidate(
            String sessionId,
            String runId,
            String approvalId,
            String reason) {
        ApprovalRecord invalidated = approvalStore.invalidate(sessionId, runId, approvalId, reason);
        emitResolution(invalidated);
        decisionSignals.remove(approvalId);
        return invalidated;
    }

    private ApprovalRecord refreshExpiration(ApprovalRecord current) {
        if (current.status() != ApprovalRecord.Status.PENDING
                || clock.instant().isBefore(current.expiresAt())) {
            return current;
        }
        return expire(current.sessionId(), current.runId(), current.approvalId());
    }

    private ApprovalRecord expire(String sessionId, String runId, String approvalId) {
        ApprovalRecord expired = approvalStore.expire(
                sessionId, runId, approvalId, clock.instant());
        emitResolution(expired);
        return expired;
    }

    private void emitResolution(ApprovalRecord record) {
        if (record.status() == ApprovalRecord.Status.PENDING) {
            return;
        }
        Sinks.One<ApprovalRecord> signal = decisionSignals.get(record.approvalId());
        if (signal != null) {
            signal.tryEmitValue(record);
        }
    }

    private ApprovalResponse response(ApprovalRecord approval) {
        RunRecord run = runStore.read(approval.sessionId(), approval.runId());
        return ApprovalResponse.from(approval, run);
    }
}
