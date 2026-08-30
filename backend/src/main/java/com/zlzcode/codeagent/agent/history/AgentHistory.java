package com.zlzcode.codeagent.agent.history;

import com.zlzcode.codeagent.agent.model.ToolDecision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 当前 Agent Run 内只追加的 LLM 消息历史。
 */
public final class AgentHistory {

    private final List<Message> messages;
    private final Set<String> knownCallIds = new HashSet<>();
    private final Set<String> completedCallIds = new HashSet<>();
    private final List<String> outstandingCallIds = new ArrayList<>();

    AgentHistory(List<Message> initialMessages) {
        if (initialMessages == null || initialMessages.isEmpty()) {
            throw new IllegalArgumentException("Agent history requires initial messages");
        }
        this.messages = new ArrayList<>(initialMessages.size());
        for (Message message : initialMessages) {
            appendInitial(message);
        }
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial Agent history contains outstanding tool calls");
        }
    }

    public List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public void appendAssistantToolCalls(
            String content,
            String reasoningContent,
            List<ToolDecision.ToolCall> toolCalls) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Previous tool calls are still outstanding");
        }
        AssistantToolCallsMessage message = new AssistantToolCallsMessage(
                content, reasoningContent, toolCalls);
        registerToolCalls(message.toolCalls());
        messages.add(message);
    }

    public void appendToolResult(String callId, String content) {
        ToolResultMessage message = new ToolResultMessage(callId, content);
        completeToolCall(message.toolCallId());
        messages.add(message);
    }

    public void appendFinalAssistant(String content) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot append final text with outstanding tool calls");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Final assistant content cannot be blank");
        }
        messages.add(new TextMessage(TextRole.ASSISTANT, content));
    }

    private void appendInitial(Message message) {
        Objects.requireNonNull(message, "Agent history message cannot be null");
        if (message instanceof AssistantToolCallsMessage assistant) {
            if (!outstandingCallIds.isEmpty()) {
                throw new IllegalArgumentException("Initial tool-call groups cannot overlap");
            }
            registerToolCalls(assistant.toolCalls());
        } else if (message instanceof ToolResultMessage result) {
            completeToolCall(result.toolCallId());
        } else if (message instanceof TextMessage text
                && text.role() == TextRole.ASSISTANT
                && !outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial assistant text precedes outstanding tool results");
        }
        messages.add(message);
    }

    private void registerToolCalls(List<ToolDecision.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call message cannot be empty");
        }
        Set<String> newIds = new HashSet<>();
        for (ToolDecision.ToolCall toolCall : toolCalls) {
            if (toolCall == null || toolCall.id() == null || toolCall.id().isBlank()) {
                throw new IllegalArgumentException("Tool call ID cannot be blank");
            }
            if (!newIds.add(toolCall.id()) || knownCallIds.contains(toolCall.id())) {
                throw new IllegalStateException("Tool call ID was already used");
            }
        }
        knownCallIds.addAll(newIds);
        outstandingCallIds.addAll(toolCalls.stream().map(ToolDecision.ToolCall::id).toList());
    }

    private void completeToolCall(String callId) {
        if (completedCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result was already appended");
        }
        if (!knownCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result references an unknown call ID");
        }
        if (outstandingCallIds.isEmpty() || !outstandingCallIds.getFirst().equals(callId)) {
            throw new IllegalStateException("Tool results must follow model tool-call order");
        }
        outstandingCallIds.removeFirst();
        completedCallIds.add(callId);
    }

    public sealed interface Message permits TextMessage, AssistantToolCallsMessage, ToolResultMessage {
    }

    public enum TextRole {
        SYSTEM,
        USER,
        ASSISTANT
    }

    public record TextMessage(TextRole role, String content) implements Message {

        public TextMessage {
            Objects.requireNonNull(role, "Text message role cannot be null");
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Text message content cannot be blank");
            }
        }

        @Override
        public String toString() {
            return "TextMessage[role=" + role + ", content=<redacted>]";
        }
    }

    public record AssistantToolCallsMessage(
            String content,
            String reasoningContent,
            List<ToolDecision.ToolCall> toolCalls) implements Message {

        public AssistantToolCallsMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        @Override
        public String toString() {
            return "AssistantToolCallsMessage[content=<redacted>, reasoningContent=<redacted>, toolCalls="
                    + toolCalls.size() + "]";
        }
    }

    public record ToolResultMessage(String toolCallId, String content) implements Message {

        public ToolResultMessage {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Tool result call ID cannot be blank");
            }
            Objects.requireNonNull(content, "Tool result content cannot be null");
        }

        @Override
        public String toString() {
            return "ToolResultMessage[toolCallId=" + toolCallId + ", content=<redacted>]";
        }
    }
}
