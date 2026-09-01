package com.zlzcode.codeagent.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
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
                    if (record.status() == RunStatus.RUNNING) {
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
                snapshot.requestFingerprint(), RunStatus.RUNNING, snapshot.createdAt(),
                snapshot.createdAt(), null, null, List.of(), null, null, null, null);
        records.put(record.runId(), record);
        try {
            persist(record);
        } catch (RuntimeException exception) {
            records.remove(record.runId());
            throw exception;
        }
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

    @Override
    public synchronized void succeed(
            String runId,
            String finalAnswer,
            List<Session.ToolHistory> toolHistory,
            AgentEvent.RunMetrics metrics) {
        RunRecord current = require(runId);
        RunRecord updated = new RunRecord(
                current.runId(), current.sessionId(), current.idempotencyKey(), current.requestFingerprint(),
                RunStatus.SUCCEEDED, current.createdAt(), Instant.now(), Instant.now(), finalAnswer,
                toolHistory, metrics, null, null, null);
        records.put(runId, updated);
        persist(updated);
    }

    @Override
    public synchronized void fail(String runId, String code, String message, boolean retryable) {
        failInternal(runId, code, message, retryable);
    }

    private void failInternal(String runId, String code, String message, boolean retryable) {
        RunRecord current = require(runId);
        RunRecord updated = new RunRecord(
                current.runId(), current.sessionId(), current.idempotencyKey(), current.requestFingerprint(),
                RunStatus.FAILED, current.createdAt(), Instant.now(), Instant.now(), null,
                current.toolHistory(), null, code, message, retryable);
        records.put(runId, updated);
        persist(updated);
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
