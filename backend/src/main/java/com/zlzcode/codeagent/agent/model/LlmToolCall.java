package com.zlzcode.codeagent.agent.model;

/**
 * 模型请求执行工具时返回的完整调用。
 */
public record LlmToolCall(String id, String name, String arguments) {

    public LlmToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("LLM tool call ID cannot be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("LLM tool call name cannot be blank");
        }
    }
}
