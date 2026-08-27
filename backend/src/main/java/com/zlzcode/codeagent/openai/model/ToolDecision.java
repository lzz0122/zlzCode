package com.zlzcode.codeagent.openai.model;

public record ToolDecision(String content, String reasoningContent, ToolCall toolCall) {

    public boolean hasToolCall() {
        return toolCall != null;
    }

    public record ToolCall(String id, String type, String name, String arguments) {
    }
}
