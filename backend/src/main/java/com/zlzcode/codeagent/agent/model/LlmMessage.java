package com.zlzcode.codeagent.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * 当前 Run 中提供给模型的消息合同。
 */
public sealed interface LlmMessage permits LlmMessage.Text,
        LlmMessage.AssistantToolCalls, LlmMessage.ToolResult {

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    record Text(Role role, String content) implements LlmMessage {

        public Text {
            Objects.requireNonNull(role, "LLM text message role cannot be null");
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("LLM text message content cannot be blank");
            }
        }

        @Override
        public String toString() {
            return "Text[role=" + role + ", content=<redacted>]";
        }
    }

    record AssistantToolCalls(
            String content,
            String hiddenReasoning,
            List<LlmToolCall> toolCalls) implements LlmMessage {

        public AssistantToolCalls {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        @Override
        public String toString() {
            return "AssistantToolCalls[content=<redacted>, hiddenReasoning=<redacted>, toolCalls="
                    + toolCalls.size() + "]";
        }
    }

    record ToolResult(String toolCallId, String content) implements LlmMessage {

        public ToolResult {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("LLM tool result call ID cannot be blank");
            }
            Objects.requireNonNull(content, "LLM tool result content cannot be null");
        }

        @Override
        public String toString() {
            return "ToolResult[toolCallId=" + toolCallId + ", content=<redacted>]";
        }
    }
}
