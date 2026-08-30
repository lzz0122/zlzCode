package com.zlzcode.codeagent.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * 当前 Run 中提供给模型的消息合同。
 */
public sealed interface LlmMessage permits LlmMessage.TextMessage,
        LlmMessage.AssistantToolCallsMessage, LlmMessage.ToolResultMessage {

    enum MessageRole {
        SYSTEM,
        USER,
        ASSISTANT
    }

    record TextMessage(MessageRole role, String content) implements LlmMessage {

        public TextMessage {
            Objects.requireNonNull(role, "LLM text message role cannot be null");
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("LLM text message content cannot be blank");
            }
        }

        @Override
        public String toString() {
            return "TextMessage[role=" + role + ", content=<redacted>]";
        }
    }

    record AssistantToolCallsMessage(
            String content,
            String hiddenReasoning,
            List<LlmToolCall> toolCalls) implements LlmMessage {

        public AssistantToolCallsMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        @Override
        public String toString() {
            return "AssistantToolCallsMessage[content=<redacted>, hiddenReasoning=<redacted>, toolCalls="
                    + toolCalls.size() + "]";
        }
    }

    record ToolResultMessage(String toolCallId, String content) implements LlmMessage {

        public ToolResultMessage {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("LLM tool result call ID cannot be blank");
            }
            Objects.requireNonNull(content, "LLM tool result content cannot be null");
        }

        @Override
        public String toString() {
            return "ToolResultMessage[toolCallId=" + toolCallId + ", content=<redacted>]";
        }
    }
}
