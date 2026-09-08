package com.zlzcode.codeagent.tool.model;

import java.util.Objects;

public record ToolCompleted(ToolOutcome outcome) implements ToolExecutionResult {

    public ToolCompleted {
        Objects.requireNonNull(outcome, "Completed tool outcome cannot be null");
    }
}
