package com.zlzcode.codeagent.agent.dto;

import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import com.zlzcode.codeagent.agent.model.RunRecord;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.tool.model.MutationOperation;

import java.time.Instant;
import java.util.List;

public record ApprovalResponse(
        String approvalId,
        String sessionId,
        String runId,
        String toolCallId,
        ApprovalRecord.Status status,
        ApprovalRecord.Decision decision,
        ApprovalRecord.CommitState commitState,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt,
        String toolName,
        MutationOperation operation,
        List<String> relativePaths,
        String presentationSummary,
        Outcome outcome,
        String reason,
        RunStatus runStatus) {

    public ApprovalResponse {
        relativePaths = List.copyOf(relativePaths);
    }

    public static ApprovalResponse from(ApprovalRecord approval, RunRecord run) {
        Outcome projectedOutcome = approval.outcome() == null
                ? null
                : new Outcome(approval.outcome().ok(), approval.outcome().presentation());
        return new ApprovalResponse(
                approval.approvalId(),
                approval.sessionId(),
                approval.runId(),
                approval.toolCallId(),
                approval.status(),
                approval.decision(),
                approval.commitState(),
                approval.createdAt(),
                approval.expiresAt(),
                approval.resolvedAt(),
                approval.plan().toolName(),
                approval.plan().operation(),
                approval.plan().relativePaths(),
                approval.plan().presentationSummary(),
                projectedOutcome,
                approval.reason(),
                run.status());
    }

    public record Outcome(boolean ok, String presentation) {
    }
}
