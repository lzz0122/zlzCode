package com.zlzcode.codeagent.agent.model;

import java.time.Instant;

public record AgentRunSnapshot(
        String runId,
        String sessionId,
        String idempotencyKey,
        String requestFingerprint,
        Instant createdAt) {
}
