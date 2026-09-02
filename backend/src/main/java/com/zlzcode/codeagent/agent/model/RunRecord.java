package com.zlzcode.codeagent.agent.model;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.session.model.Session;

import java.time.Instant;
import java.util.List;

public record RunRecord(
        String runId,
        String sessionId,
        String idempotencyKey,
        String requestFingerprint,
        RunStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String finalAnswer,
        List<Session.ToolHistory> toolHistory,
        AgentEvent.RunMetrics metrics,
        String errorCode,
        String errorMessage,
        Boolean errorRetryable) {

    public RunRecord {
        toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
    }
}
