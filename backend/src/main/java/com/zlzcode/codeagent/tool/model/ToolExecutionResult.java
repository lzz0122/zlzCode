package com.zlzcode.codeagent.tool.model;

public sealed interface ToolExecutionResult permits ToolCompleted, ApprovalRequired {
}
