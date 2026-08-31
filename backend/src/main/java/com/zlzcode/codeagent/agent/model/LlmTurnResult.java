package com.zlzcode.codeagent.agent.model;

import java.util.List;

/**
 * 一次模型调用收口后的 Provider-neutral 结果。
 */
public record LlmTurnResult(
        String content,
        String hiddenReasoning,
        List<LlmToolCall> toolCalls,
        Integer inputTokens,
        Integer outputTokens,
        LlmStreamEvent.StopReason stopReason) {

    public LlmTurnResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
