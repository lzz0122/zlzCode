package com.zlzcode.codeagent.agent.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次模型调用的 Provider-neutral 请求合同。
 */
public record LlmRequest(
        String model,
        String reasoningEffort,
        List<LlmMessage> messages,
        List<ToolDeclaration> availableTools) {

    public LlmRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("LLM request model cannot be blank");
        }
        model = model.trim();
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null : reasoningEffort.trim();
        messages = List.copyOf(Objects.requireNonNull(messages, "LLM request messages cannot be null"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("LLM request messages cannot be empty");
        }
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
    }

    /**
     * 当前请求向模型公开的工具描述；工具执行和参数校验仍由 tool 层拥有。
     */
    public record ToolDeclaration(String name, String description, Map<String, Object> parametersSchema) {

        public ToolDeclaration {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("LLM tool name cannot be blank");
            }
            Objects.requireNonNull(description, "LLM tool description cannot be null");
            parametersSchema = Map.copyOf(Objects.requireNonNull(
                    parametersSchema, "LLM tool parameters schema cannot be null"));
        }
    }
}
