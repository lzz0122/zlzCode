package com.zlzcode.codeagent.tool.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.service.WorkspaceOverviewService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
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
     * 背景：每轮 LLM 请求都需要工具名称、描述和参数 Schema，但这些信息本来由工具定义拥有。
     * 设计意图：由 Registry 从已注册定义生成模型声明，让 Agent 只获取工具快照，不重复组装元数据。
     * 关键约束：只能暴露 available 的注册工具，执行校验仍必须经过 Registry，不能把不可用工具或裸 Schema 直接交给模型。
     */
    public List<LlmRequest.ToolDeclaration> modelToolDeclarations() {
        return tools.values().stream()
                .filter(RegisteredTool::available)
                .map(tool -> new LlmRequest.ToolDeclaration(
                        tool.definition().name(),
                        tool.definition().description(),
                        tool.definition().parametersSchema()))
                .toList();
    }

    /*
     * 背景：Agent 系统提示词需要引用当前可用工具名称，名称和提示词内容不能在多个层重复维护。
     * 设计意图：由 Registry 提供提示词所需的已注册工具名称，Agent 只负责把它交给 LLM 合同。
     * 关键约束：当前提示词合同只支持一个工作区工具；若改变工具选择规则，必须同步修改 AgentLlmContract，不能静默取任意名称。
     */
    public String promptToolName() {
        return tools.values().stream()
                .filter(RegisteredTool::available)
                .map(tool -> tool.definition().name())
                .findFirst()
                .orElse("");
    }

    public String displayName(String name) {
        return find(name).displayName();
    }

    public Mono<ToolOutcome> execute(
            String name,
            AuthorizedWorkspace workspace,
            String arguments) {
        return execute(find(name), workspace, arguments);
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
