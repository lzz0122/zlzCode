package com.zlzcode.codeagent.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.agent.exception.RunException;
import com.zlzcode.codeagent.agent.model.AgentRunSnapshot;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.session.model.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class FileRunStore implements RunStore {

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Map<String, RunRecord> records = new HashMap<>();

    public FileRunStore(
            ObjectMapper objectMapper,
            @Value("${codeagent.run.directory:}") String configuredDirectory) {
        this.objectMapper = objectMapper;
        this.directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? defaultDirectory()
                : Path.of(configuredDirectory.trim()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() {
        synchronized (this) {
            try {
                Files.createDirectories(directory);
                try (var files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(this::loadFile);
                }
                for (RunRecord record : List.copyOf(records.values())) {
                    if (record.status() == RunStatus.RUNNING
                            || record.status() == RunStatus.WAITING_APPROVAL) {
                        failInternal(record.runId(), "RUN_INTERRUPTED", "服务重启导致 Run 未完成", false);
                    }
                }
            } catch (IOException exception) {
                throw RunException.stateCorrupted();
            }
        }
    }

    @Override
    public synchronized Optional<RunRecord> findByIdempotency(String sessionId, String idempotencyKey) {
        return records.values().stream()
                .filter(record -> record.sessionId().equals(sessionId)
                        && record.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public synchronized RunRecord create(AgentRunSnapshot snapshot) {
        RunRecord record = new RunRecord(
                snapshot.runId(), snapshot.sessionId(), snapshot.idempotencyKey(),
                snapshot.requestFingerprint(), RunStatus.RUNNING, null, null, snapshot.createdAt(),
                snapshot.createdAt(), null, null, List.of(), null, null, null, null);
        persist(record);
        records.put(record.runId(), record);
        return record;
    }

    @Override
    public synchronized RunRecord read(String sessionId, String runId) {
        RunRecord record = records.get(runId);
        if (record == null || !record.sessionId().equals(sessionId)) {
            throw RunException.notFound();
        }
        return record;
    }

    /*
     * 背景：审批等待暂停的是原 Run，不能被另一个审批或普通成功收口覆盖。
     * 设计意图：用 RUNNING 与 WAITING_APPROVAL 的条件转换绑定唯一 approvalId，并在落盘后发布新快照。
     * 关键约束：离开等待必须匹配原审批，且等待状态不能直接成功；否则工具结果可能串到错误调用或提前完成 Run。
     */
    @Override
    public synchronized RunRecord enterWaitingApproval(
            String runId,
            String approvalId,
            String toolCallId) {
        if (approvalId == null || approvalId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            throw ApprovalException.stateConflict();
        }
        RunRecord current = require(runId);
        if (current.status() != RunStatus.RUNNING) {
            throw ApprovalException.stateConflict();
        }
        RunRecord updated = copyWithState(
                current, RunStatus.WAITING_APPROVAL, approvalId, toolCallId,
                Instant.now(), null, current.finalAnswer(), current.toolHistory(), current.metrics(),
                current.errorCode(), current.errorMessage(), current.errorRetryable());
        persist(updated);
        records.put(runId, updated);
        return updated;
    }

    @Override
    public synchronized RunRecord leaveWaitingApproval(String runId, String approvalId) {
        RunRecord current = require(runId);
        if (current.status() != RunStatus.WAITING_APPROVAL
                || approvalId == null
                || !approvalId.equals(current.pendingApprovalId())) {
            throw ApprovalException.stateConflict();
        }
        RunRecord updated = copyWithState(
                current, RunStatus.RUNNING, null, null,
                Instant.now(), null, current.finalAnswer(), current.toolHistory(), current.metrics(),
                current.errorCode(), current.errorMessage(), current.errorRetryable());
        persist(updated);
        records.put(runId, updated);
        return updated;
    }

    @Override
    public synchronized void succeed(
            String runId,
            String finalAnswer,
            List<Session.ToolHistory> toolHistory,
            AgentEvent.RunMetrics metrics) {
        RunRecord current = require(runId);
        if (current.status() == RunStatus.SUCCEEDED || current.status() == RunStatus.FAILED) {
            return;
        }
        if (current.status() != RunStatus.RUNNING) {
            throw ApprovalException.stateConflict();
        }
        Instant now = Instant.now();
        RunRecord updated = copyWithState(
                current, RunStatus.SUCCEEDED, null, null, now, now, finalAnswer,
                toolHistory, metrics, null, null, null);
        persist(updated);
        records.put(runId, updated);
    }

    @Override
    public synchronized void fail(String runId, String code, String message, boolean retryable) {
        failInternal(runId, code, message, retryable);
    }

    private void failInternal(String runId, String code, String message, boolean retryable) {
        RunRecord current = require(runId);
        if (current.status() == RunStatus.SUCCEEDED || current.status() == RunStatus.FAILED) {
            return;
        }
        if (current.status() != RunStatus.RUNNING
                && current.status() != RunStatus.WAITING_APPROVAL) {
            throw ApprovalException.stateConflict();
        }
        Instant now = Instant.now();
        RunRecord updated = copyWithState(
                current, RunStatus.FAILED, null, null, now, now, null,
                current.toolHistory(), null, code, message, retryable);
        persist(updated);
        records.put(runId, updated);
    }

    private RunRecord copyWithState(
            RunRecord current,
            RunStatus status,
            String pendingApprovalId,
            String pendingToolCallId,
            Instant updatedAt,
            Instant completedAt,
            String finalAnswer,
            List<Session.ToolHistory> toolHistory,
            AgentEvent.RunMetrics metrics,
            String errorCode,
            String errorMessage,
            Boolean errorRetryable) {
        return new RunRecord(
                current.runId(), current.sessionId(), current.idempotencyKey(), current.requestFingerprint(),
                status, pendingApprovalId, pendingToolCallId, current.createdAt(), updatedAt, completedAt,
                finalAnswer, toolHistory, metrics, errorCode, errorMessage, errorRetryable);
    }

    private RunRecord require(String runId) {
        RunRecord record = records.get(runId);
        if (record == null) throw RunException.notFound();
        return record;
    }

    private void loadFile(Path path) {
        try {
            RunRecord record = objectMapper.readValue(path.toFile(), RunRecord.class);
            if (record == null || record.runId() == null || record.sessionId() == null
                    || record.status() == null) throw RunException.stateCorrupted();
            records.put(record.runId(), record);
        } catch (IOException | RuntimeException exception) {
            throw RunException.stateCorrupted();
        }
    }

    private void persist(RunRecord record) {
        Path target = directory.resolve(record.runId() + ".json");
        Path temporary = directory.resolve("." + record.runId() + ".tmp");
        try {
            Files.createDirectories(directory);
            objectMapper.writeValue(temporary.toFile(), record);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw RunException.stateCorrupted();
        }
    }

    private static Path defaultDirectory() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (working.getFileName() != null && "backend".equalsIgnoreCase(working.getFileName().toString())) {
            return working.getParent().resolve("runs");
        }
        return working.resolve("runs");
    }
}
