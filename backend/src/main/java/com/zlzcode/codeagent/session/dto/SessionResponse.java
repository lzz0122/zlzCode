package com.zlzcode.codeagent.session.dto;

import com.zlzcode.codeagent.session.model.Session;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record SessionResponse(
        String sessionId,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt,
        List<TurnResponse> turns) {

    public static SessionResponse from(Session session) {
        return new SessionResponse(
                session.sessionId(),
                session.workspaceId(),
                session.createdAt(),
                session.updatedAt(),
                session.turns().stream().map(TurnResponse::from).toList());
    }

    public record TurnResponse(
            String runId,
            String state,
            Instant createdAt,
            UserResponse user,
            AssistantResponse assistant) {

        private static TurnResponse from(Session.Turn turn) {
            return new TurnResponse(
                    turn.runId(),
                    turn.state().name().toLowerCase(Locale.ROOT),
                    turn.createdAt(),
                    new UserResponse(turn.user().content()),
                    turn.assistant() == null ? null : AssistantResponse.from(turn.assistant()));
        }
    }

    public record UserResponse(String content) {
    }

    public record AssistantResponse(String content, List<ToolHistoryResponse> toolHistory) {

        private static AssistantResponse from(Session.AssistantMessage assistant) {
            return new AssistantResponse(
                    assistant.content(),
                    assistant.toolHistory().stream().map(ToolHistoryResponse::from).toList());
        }
    }

    public record ToolHistoryResponse(String name, String arguments, String result) {

        private static ToolHistoryResponse from(Session.ToolHistory history) {
            return new ToolHistoryResponse(history.name(), history.arguments(), history.result());
        }
    }
}
