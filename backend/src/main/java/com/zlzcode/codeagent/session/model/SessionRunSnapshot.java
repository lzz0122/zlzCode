package com.zlzcode.codeagent.session.model;

import java.util.List;

/**
 * Session 在某个 Run 真正开始执行时提供的不可变快照。
 */
public record SessionRunSnapshot(
        String sessionId,
        String runId,
        String workspaceId,
        String prompt,
        List<Session.Turn> completedHistory) {

    public SessionRunSnapshot {
        completedHistory = completedHistory == null ? List.of() : List.copyOf(completedHistory);
    }
}
