package com.zlzcode.codeagent.agent.model;

import com.zlzcode.codeagent.tool.model.MutationPlan;
import com.zlzcode.codeagent.tool.model.ToolOutcome;

import java.time.Instant;
import java.util.Objects;

public record ApprovalRecord(
        String approvalId,
        String sessionId,
        String runId,
        String toolCallId,
        String workspaceId,
        MutationPlan plan,
        Status status,
        Decision decision,
        CommitState commitState,
        ToolOutcome outcome,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt,
        Instant updatedAt,
        String reason) {

    public ApprovalRecord {
        requireNonBlank(approvalId, "Approval ID");
        requireNonBlank(sessionId, "Approval session ID");
        requireNonBlank(runId, "Approval run ID");
        requireNonBlank(toolCallId, "Approval tool call ID");
        requireNonBlank(workspaceId, "Approval workspace ID");
        Objects.requireNonNull(plan, "Approval mutation plan cannot be null");
        Objects.requireNonNull(status, "Approval status cannot be null");
        Objects.requireNonNull(commitState, "Approval commit state cannot be null");
        Objects.requireNonNull(createdAt, "Approval creation time cannot be null");
        Objects.requireNonNull(expiresAt, "Approval expiration time cannot be null");
        Objects.requireNonNull(updatedAt, "Approval update time cannot be null");

        if (!runId.equals(plan.runId()) || !toolCallId.equals(plan.toolCallId())) {
            throw new IllegalArgumentException("Approval identity must match its mutation plan");
        }
        if (!expiresAt.isAfter(createdAt)
                || updatedAt.isBefore(createdAt)
                || resolvedAt != null && resolvedAt.isBefore(createdAt)
                || resolvedAt != null && updatedAt.isBefore(resolvedAt)) {
            throw new IllegalArgumentException("Approval timestamps are inconsistent");
        }
        if (status == Status.PENDING && (decision != null || resolvedAt != null)) {
            throw new IllegalArgumentException("Pending approval cannot contain a resolution");
        }
        if (status == Status.DECIDED && (decision == null || resolvedAt == null)) {
            throw new IllegalArgumentException("Decided approval must contain its decision and resolution time");
        }
        if (commitState == CommitState.COMPLETED && outcome == null
                || commitState != CommitState.COMPLETED && outcome != null) {
            throw new IllegalArgumentException("Approval outcome must match completed commit state");
        }
    }

    public enum Status {
        PENDING,
        DECIDED,
        EXPIRED,
        INVALIDATED
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public enum CommitState {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        UNKNOWN
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
