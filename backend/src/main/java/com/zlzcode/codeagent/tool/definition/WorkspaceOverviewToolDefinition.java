package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工作区概览工具的唯一元数据定义。
 */
@Component
public final class WorkspaceOverviewToolDefinition implements ToolDefinition {

    private static final String NAME = "list_workspace_entries";
    private static final String DISPLAY_NAME = "查看工作区根目录";
    private static final String DESCRIPTION =
            "List the immediate files and directories at the root of the currently selected code workspace. "
                    + "Use this before making claims about the project's top-level structure.";
    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of(),
            "additionalProperties", false);

    private final ObjectMapper objectMapper;

    public WorkspaceOverviewToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    /*
     * 背景：工作区工具当前只接受空 JSON 对象，模型传入其他参数可能导致未授权的文件访问。
     * 设计意图：由工具定义同时拥有参数契约和校验规则，而不是让 Agent 编排层重复解释 Schema。
     * 关键约束：参数必须是有效的 JSON 对象且不能包含字段；放宽为 null、空字符串或任意对象都会破坏工具边界。
     */
    @Override
    public Validation validate(String arguments) {
        if (arguments == null || arguments.isBlank()) return Validation.rejected(
                "TOOL_ARGUMENTS_INVALID", "工具参数无效，未执行工作区访问");
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return node != null && node.isObject() && node.isEmpty()
                    ? Validation.accepted()
                    : Validation.rejected("TOOL_ARGUMENTS_INVALID", "工具参数无效，未执行工作区访问");
        } catch (Exception exception) {
            return Validation.rejected("TOOL_ARGUMENTS_INVALID", "工具参数无效，未执行工作区访问");
        }
    }
}
