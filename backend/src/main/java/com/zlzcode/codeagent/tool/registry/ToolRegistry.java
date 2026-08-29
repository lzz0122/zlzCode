package com.zlzcode.codeagent.tool.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.service.WorkspaceOverviewService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public final class ToolRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, RegisteredTool> tools;

    public ToolRegistry(
            ObjectMapper objectMapper,
            WorkspaceOverviewToolDefinition definition,
            WorkspaceOverviewService handler) {
        this.objectMapper = objectMapper;
        this.tools = Map.of(definition.name(), new RegisteredTool(definition, handler, true));
    }

    public RegisteredTool find(String name) {
        return tools.getOrDefault(name, RegisteredTool.unavailable());
    }

    /*
     * 背景：模型返回的工具名称和参数必须经过同一条边界才能进入本机文件操作。
     * 设计意图：由 Registry 串联查找后的定义校验与 Handler 执行，避免 Agent 层重复实现工具协议。
     * 关键约束：必须先完成 validate 再调用 handler；任何绕过校验的路径都可能把未授权参数传入工具。
     */
    public Mono<ToolOutcome> execute(
            RegisteredTool tool,
            AuthorizedWorkspace workspace,
            String arguments) {
        if (!tool.available()) {
            return Mono.just(ToolOutcome.failure(
                    objectMapper,
                    "TOOL_NOT_AVAILABLE",
                    "未执行未知工具",
                    "The requested tool is not available."));
        }

        ToolDefinition.Validation validation = tool.definition().validate(arguments);
        if (!validation.valid()) {
            return Mono.just(ToolOutcome.failure(
                    objectMapper,
                    validation.code(),
                    validation.presentation(),
                    "The tool arguments are invalid."));
        }
        return tool.handler().execute(workspace, arguments);
    }

    public record RegisteredTool(
            ToolDefinition definition,
            ToolHandler handler,
            boolean available) {

        private static RegisteredTool unavailable() {
            return new RegisteredTool(null, null, false);
        }

        public String displayName() {
            return available ? definition.displayName() : "未知工具";
        }
    }
}
