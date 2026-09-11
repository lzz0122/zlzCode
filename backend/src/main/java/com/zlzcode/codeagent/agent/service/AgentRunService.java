package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.agent.exception.RunException;
import com.zlzcode.codeagent.agent.model.AgentRunSnapshot;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.store.RunStore;
import com.zlzcode.codeagent.session.service.SessionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentRunService {

    private final SessionService sessionService;
    private final RunStore runStore;
    private final AgentRunExecutor runExecutor;

    public AgentRunService(
            SessionService sessionService,
            RunStore runStore,
            AgentRunExecutor runExecutor) {
        this.sessionService = sessionService;
        this.runStore = runStore;
        this.runExecutor = runExecutor;
    }

    /*
     * 背景：HTTP 重试可能在第一次提交已落盘后再次到达，必须避免重复创建 Turn 和重复执行工具。
     * 设计意图：先按 Session 作用域的幂等键复用或拒绝冲突请求，再持久化 RUNNING 并交给 Executor 启动。
     * 关键约束：RunStore 成功记录 RUNNING 后才能启动执行；不能把幂等判断放到异步任务中，否则响应与执行会脱节。
     */
    public synchronized RunResponse submit(AgentRunRequest request) {
        String key = request.idempotencyKey().trim();
        String requestFingerprint = fingerprint(request);
        Optional<RunRecord> existing = runStore.findByIdempotency(request.sessionId(), key);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(requestFingerprint)) {
                throw RunException.idempotencyConflict();
            }
            return RunResponse.from(existing.get());
        }

        String runId = "run-" + UUID.randomUUID();
        sessionService.beginRun(request.sessionId(), runId, request.prompt());
        RunRecord record = runStore.create(new AgentRunSnapshot(
                runId, request.sessionId(), key, requestFingerprint, Instant.now()));
        RunExecution execution = new RunExecution(
                runId,
                request.sessionId(),
                request.prompt(),
                new RunExecution.RunOptions(
                        request.model(),
                        request.reasoningEffort(),
                        request.openai()));
        runExecutor.start(execution);
        return RunResponse.from(record);
    }

    public Flux<AgentEvent> events(String sessionId, String runId) {
        return runExecutor.events(sessionId, runId);
    }

    public RunResponse read(String sessionId, String runId) {
        return RunResponse.from(runStore.read(sessionId, runId));
    }

    private String fingerprint(AgentRunRequest request) {
        String value = String.join("\u0000", request.sessionId(), request.prompt(), request.model(),
                request.reasoningEffort() == null ? "" : request.reasoningEffort(),
                request.openai().baseUrl(), request.openai().apiKey());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
