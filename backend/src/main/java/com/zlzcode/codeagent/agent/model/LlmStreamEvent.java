package com.zlzcode.codeagent.agent.model;

/**
 * 模型流中可由 Agent 消费的 Provider-neutral 事件。
 */
public sealed interface LlmStreamEvent permits LlmStreamEvent.TextDelta,
        LlmStreamEvent.HiddenReasoningDelta, LlmStreamEvent.ToolCallDelta,
        LlmStreamEvent.Usage, LlmStreamEvent.Finish {

    record TextDelta(String value) implements LlmStreamEvent {
    }

    record HiddenReasoningDelta(String value) implements LlmStreamEvent {
    }

    record ToolCallDelta(
            int index,
            String id,
            String name,
            String argumentsDelta) implements LlmStreamEvent {

        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException("LLM tool call delta index cannot be negative");
            }
        }
    }

    record Usage(Integer inputTokens, Integer outputTokens) implements LlmStreamEvent {
    }

    record Finish(Reason reason) implements LlmStreamEvent {

        public enum Reason {
            STOP,
            TOOL_CALLS,
            LENGTH,
            CONTENT_FILTER,
            OTHER
        }
    }
}
