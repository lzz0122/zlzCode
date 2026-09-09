package com.zlzcode.codeagent.agent.store;

import com.zlzcode.codeagent.agent.model.AgentRunSnapshot;
import com.zlzcode.codeagent.agent.model.RunRecord;

import java.util.Optional;

public interface RunStore {

    Optional<RunRecord> findByIdempotency(String sessionId, String idempotencyKey);

    RunRecord create(AgentRunSnapshot snapshot);

    RunRecord read(String sessionId, String runId);

    RunRecord enterWaitingApproval(String runId, String approvalId, String toolCallId);

    RunRecord leaveWaitingApproval(String runId, String approvalId);

    void succeed(String runId, String finalAnswer, java.util.List<com.zlzcode.codeagent.session.model.Session.ToolHistory> toolHistory,
                 com.zlzcode.codeagent.agent.dto.AgentEvent.RunMetrics metrics);

    void fail(String runId, String code, String message, boolean retryable);
}
