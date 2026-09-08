package com.zlzcode.codeagent.tool.model;

import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;

import java.util.Objects;

public record ToolExecutionContext(
        String runId, String toolCallId, AuthorizedWorkspace workspace) {

    public ToolExecutionContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Tool execution run ID cannot be blank");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("Tool execution call ID cannot be blank");
        }
        Objects.requireNonNull(workspace, "Tool execution workspace cannot be null");
    }
}
