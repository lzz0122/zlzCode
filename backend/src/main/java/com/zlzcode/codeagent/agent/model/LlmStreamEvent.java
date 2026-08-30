package com.zlzcode.codeagent.agent.model;

/**
 * 模型流中可由 Agent 消费的 Provider-neutral 事件。
 */
public sealed interface LlmStreamEvent permits LlmStreamEvent.AssistantTextChunk,
        LlmStreamEvent.InternalReasoningChunk, LlmStreamEvent.ToolCallFragment,
        LlmStreamEvent.TokenUsage, LlmStreamEvent.GenerationFinished {

    record AssistantTextChunk(String text) implements LlmStreamEvent {
    }

    record InternalReasoningChunk(String text) implements LlmStreamEvent {
    }

    record ToolCallFragment(
            int toolCallIndex,
            String callId,
            String toolName,
            String argumentsFragment) implements LlmStreamEvent {

        public ToolCallFragment {
            if (toolCallIndex < 0) {
                throw new IllegalArgumentException("LLM tool call fragment index cannot be negative");
            }
        }
    }

    record TokenUsage(Integer inputTokens, Integer outputTokens) implements LlmStreamEvent {
    }

    record GenerationFinished(StopReason stopReason) implements LlmStreamEvent {
    }

    enum StopReason {
        STOP,
        TOOL_CALLS,
        LENGTH,
        CONTENT_FILTER,
        OTHER
    }
}
