package com.zlzcode.codeagent.session.model;

import java.time.Instant;
import java.util.List;

public record Session(
        String sessionId,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt,
        List<Turn> turns) {

    public Session {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public enum TurnState {
        INCOMPLETE,
        COMPLETED
    }

    public record Turn(
            String runId,
            TurnState state,
            Instant createdAt,
            UserMessage user,
            AssistantMessage assistant) {
    }

    public record UserMessage(String content) {
    }

    public record AssistantMessage(String content, List<ToolHistory> toolHistory) {

        public AssistantMessage {
            toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
        }
    }

    public record ToolHistory(String name, String arguments, String result) {
    }
}
