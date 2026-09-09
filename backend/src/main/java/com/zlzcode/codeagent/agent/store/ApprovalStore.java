package com.zlzcode.codeagent.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import com.zlzcode.codeagent.agent.model.ApprovalRecord.CommitState;
import com.zlzcode.codeagent.agent.model.ApprovalRecord.Decision;
import com.zlzcode.codeagent.agent.model.ApprovalRecord.Status;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public final class ApprovalStore {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final String RESTART_INVALIDATION_REASON = "服务重启导致审批失效";
    private static final String RESTART_UNKNOWN_REASON = "服务重启后提交结果无法确认";
    private static final String EXPIRED_REASON = "审批期限已到";

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Map<String, ApprovalRecord> records = new HashMap<>();

    @Autowired
    public ApprovalStore(
            ObjectMapper objectMapper,
            @Value("${codeagent.approval.directory:}") String configuredDirectory) {
        this(objectMapper, configuredDirectory == null || configuredDirectory.isBlank()
                ? defaultDirectory()
                : Path.of(configuredDirectory.trim()));
    }

    public ApprovalStore(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "Approval ObjectMapper cannot be null");
        this.directory = Objects.requireNonNull(directory, "Approval directory cannot be null")
                .toAbsolutePath().normalize();
    }

    @PostConstruct
    /*
     * 背景：本阶段不恢复跨重启的等待流程，旧进程留下的审批不能继续驱动原工具调用。
     * 设计意图：启动时统一失效待决定记录，并把已开始但无结果的提交标成 UNKNOWN，不尝试重放。
     * 关键约束：不能把 IN_PROGRESS 改回 NOT_STARTED 或自动调用提交，否则重启可能造成同一修改重复执行。
     */
    void load() {
        synchronized (this) {
            try {
                Files.createDirectories(directory);
                try (var files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(this::loadFile);
                }
                for (ApprovalRecord record : List.copyOf(records.values())) {
                    if (record.status() == Status.PENDING) {
                        invalidate(record.sessionId(), record.runId(), record.approvalId(),
                                RESTART_INVALIDATION_REASON);
                    } else if (record.commitState() == CommitState.IN_PROGRESS) {
                        markCommitUnknown(record.sessionId(), record.runId(), record.approvalId(),
                                RESTART_UNKNOWN_REASON);
                    }
                }
            } catch (ApprovalException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw ApprovalException.persistenceFailed();
            }
        }
    }

    public synchronized ApprovalRecord create(ApprovalRecord record) {
        Objects.requireNonNull(record, "Approval record cannot be null");
        if (!validId(record.approvalId()) || records.containsKey(record.approvalId())) {
            throw ApprovalException.stateConflict();
        }
        persist(record, false);
        records.put(record.approvalId(), record);
        return record;
    }

    public synchronized ApprovalRecord read(String sessionId, String runId, String approvalId) {
        return require(sessionId, runId, approvalId);
    }

    /*
     * 背景：重复 HTTP、期限触发和后台续执行可能竞争更新同一审批记录。
     * 设计意图：所有业务更新都在组件锁内检查当前状态，先原子落盘再替换内存快照，不开放任意覆盖入口。
     * 关键约束：第一次决定和第一次提交资格必须保持唯一；绕过条件更新会导致用户决定被覆盖或 commit 重复执行。
     */
    public synchronized ApprovalRecord decide(
            String sessionId,
            String runId,
            String approvalId,
            Decision decision,
            Instant now) {
        Objects.requireNonNull(decision, "Approval decision cannot be null");
        Objects.requireNonNull(now, "Approval decision time cannot be null");
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.status() != Status.PENDING) {
            return current;
        }
        if (!now.isBefore(current.expiresAt())) {
            return save(expiredRecord(current, now));
        }
        return save(copy(current, Status.DECIDED, decision, CommitState.NOT_STARTED,
                null, now, now, null));
    }

    public synchronized ApprovalRecord expire(
            String sessionId,
            String runId,
            String approvalId,
            Instant now) {
        Objects.requireNonNull(now, "Approval expiration time cannot be null");
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.status() != Status.PENDING || now.isBefore(current.expiresAt())) {
            return current;
        }
        return save(expiredRecord(current, now));
    }

    public synchronized ApprovalRecord invalidate(
            String sessionId,
            String runId,
            String approvalId,
            String reason) {
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.status() != Status.PENDING) {
            return current;
        }
        Instant now = Instant.now();
        return save(copy(current, Status.INVALIDATED, null, CommitState.NOT_STARTED,
                null, now, now, requireReason(reason)));
    }

    public synchronized ApprovalRecord startCommit(
            String sessionId,
            String runId,
            String approvalId) {
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.status() != Status.DECIDED
                || current.decision() != Decision.APPROVE
                || current.commitState() != CommitState.NOT_STARTED) {
            throw ApprovalException.stateConflict();
        }
        return save(copy(current, current.status(), current.decision(), CommitState.IN_PROGRESS,
                null, current.resolvedAt(), Instant.now(), current.reason()));
    }

    public synchronized ApprovalRecord completeCommit(
            String sessionId,
            String runId,
            String approvalId,
            ToolOutcome outcome) {
        Objects.requireNonNull(outcome, "Approval commit outcome cannot be null");
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.status() != Status.DECIDED
                || current.decision() != Decision.APPROVE
                || current.commitState() != CommitState.IN_PROGRESS) {
            throw ApprovalException.stateConflict();
        }
        return save(copy(current, current.status(), current.decision(), CommitState.COMPLETED,
                outcome, current.resolvedAt(), Instant.now(), current.reason()));
    }

    public synchronized ApprovalRecord markCommitUnknown(
            String sessionId,
            String runId,
            String approvalId,
            String reason) {
        ApprovalRecord current = require(sessionId, runId, approvalId);
        if (current.commitState() == CommitState.COMPLETED
                || current.commitState() == CommitState.UNKNOWN) {
            return current;
        }
        if (current.status() != Status.DECIDED
                || current.decision() != Decision.APPROVE
                || current.commitState() != CommitState.IN_PROGRESS) {
            throw ApprovalException.stateConflict();
        }
        return save(copy(current, current.status(), current.decision(), CommitState.UNKNOWN,
                null, current.resolvedAt(), Instant.now(), requireReason(reason)));
    }

    private ApprovalRecord require(String sessionId, String runId, String approvalId) {
        ApprovalRecord record = records.get(approvalId);
        if (record == null
                || !record.sessionId().equals(sessionId)
                || !record.runId().equals(runId)) {
            throw ApprovalException.notFound();
        }
        return record;
    }

    private ApprovalRecord save(ApprovalRecord updated) {
        persist(updated, true);
        records.put(updated.approvalId(), updated);
        return updated;
    }

    private ApprovalRecord expiredRecord(ApprovalRecord current, Instant now) {
        return copy(current, Status.EXPIRED, null, CommitState.NOT_STARTED,
                null, now, now, EXPIRED_REASON);
    }

    private ApprovalRecord copy(
            ApprovalRecord current,
            Status status,
            Decision decision,
            CommitState commitState,
            ToolOutcome outcome,
            Instant resolvedAt,
            Instant updatedAt,
            String reason) {
        return new ApprovalRecord(
                current.approvalId(), current.sessionId(), current.runId(), current.toolCallId(),
                current.workspaceId(), current.plan(), status, decision, commitState, outcome,
                current.createdAt(), current.expiresAt(), resolvedAt, updatedAt, reason);
    }

    private void loadFile(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw ApprovalException.persistenceFailed();
            }
            ApprovalRecord record = objectMapper.readValue(path.toFile(), ApprovalRecord.class);
            String expectedName = record == null ? null : record.approvalId() + ".json";
            if (record == null
                    || !validId(record.approvalId())
                    || !path.getFileName().toString().equals(expectedName)
                    || records.putIfAbsent(record.approvalId(), record) != null) {
                throw ApprovalException.persistenceFailed();
            }
        } catch (ApprovalException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw ApprovalException.persistenceFailed();
        }
    }

    /*
     * 背景：审批记录决定后续能否提交固定计划，进程或磁盘写入中断不能让内存状态领先于落盘事实。
     * 设计意图：先把完整记录写入同目录临时文件并强制刷新，再原子替换目标，成功后由调用方发布到内存。
     * 关键约束：不能直接覆盖正式文件或在原子移动不支持时降级，否则重启后可能读取半个记录或错误提交计划。
     */
    private void persist(ApprovalRecord record, boolean replaceExisting) {
        Path target = recordFile(record.approvalId());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target)) {
                throw ApprovalException.persistenceFailed();
            }
            if (replaceExisting && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw ApprovalException.persistenceFailed();
            }
            if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw ApprovalException.stateConflict();
            }

            byte[] encoded = objectMapper.writeValueAsBytes(record);
            temporary = Files.createTempFile(directory, ".approval-", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            if (replaceExisting) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temporary = null;
        } catch (ApprovalException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw ApprovalException.persistenceFailed();
        } catch (IOException | RuntimeException exception) {
            throw ApprovalException.persistenceFailed();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Path recordFile(String approvalId) {
        if (!validId(approvalId)) {
            throw ApprovalException.notFound();
        }
        return directory.resolve(approvalId + ".json");
    }

    private boolean validId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApprovalException.stateConflict();
        }
        return reason;
    }

    private static Path defaultDirectory() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (working.getFileName() != null && "backend".equalsIgnoreCase(working.getFileName().toString())) {
            return working.getParent().resolve("approvals");
        }
        return working.resolve("approvals");
    }
}
