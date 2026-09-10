package com.zlzcode.codeagent.tool.registry;

import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.tool.handler.ToolHandler;

import java.util.Objects;

/**
 * 背景：真实工具注册需要完整配对，而 Registry 的未知工具结果允许空定义和空执行器。
 * 设计意图：在装配输入上校验配对，不把同样的构造约束加到既有 RegisteredTool 上。
 * 关键约束：未知工具哨兵不能用本类型代替，否则未知名称查找会变成构造失败。
 */
public record ToolRegistration(
        ToolDefinition definition,
        ToolHandler handler,
        boolean available) {

    public ToolRegistration {
        Objects.requireNonNull(definition, "Tool registration definition cannot be null");
        Objects.requireNonNull(handler, "Tool registration handler cannot be null");
    }
}
