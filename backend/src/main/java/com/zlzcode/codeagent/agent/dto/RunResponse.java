package com.zlzcode.codeagent.agent.dto;

import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.session.model.Session;

import java.time.Instant;
import java.util.List;

public record RunResponse(
        String runId,
        String sessionId,
        RunStatus status,
        String pendingApprovalId,
        String pendingToolCallId,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String finalAnswer,
        List<Session.ToolHistory> toolHistory,
        AgentEvent.RunMetrics metrics,
        String errorCode,
        String errorMessage,
        Boolean errorRetryable) {

    public static RunResponse from(RunRecord record) {
        return new RunResponse(
                record.runId(), record.sessionId(), record.status(), record.pendingApprovalId(),
                record.pendingToolCallId(), record.createdAt(), record.updatedAt(), record.completedAt(),
                record.finalAnswer(), record.toolHistory(), record.metrics(), record.errorCode(),
                record.errorMessage(), record.errorRetryable());
    }
}
