package com.zlzcode.codeagent.tool.model;

import java.util.Objects;

public record ApprovalRequired(MutationPlan plan) implements ToolExecutionResult {

    public ApprovalRequired {
        Objects.requireNonNull(plan, "Approval mutation plan cannot be null");
    }
}
