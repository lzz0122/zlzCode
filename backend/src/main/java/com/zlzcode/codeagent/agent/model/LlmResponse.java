package com.zlzcode.codeagent.agent.model;

import java.util.List;

/**
 * 非流式模型调用返回的 Provider-neutral 响应。
 */
public record LlmResponse(
        String content,
        String hiddenReasoning,
        List<LlmToolCall> toolCalls) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
