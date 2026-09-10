package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class GrepToolDefinition implements ToolDefinition {

    private static final Set<String> FIELDS = Set.of("pattern", "path", "include", "force");
    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "pattern", Map.of("type", "string", "minLength", 1),
                    "path", Map.of("type", "string"),
                    "include", Map.of("type", "string", "minLength", 1),
                    "force", Map.of("type", "boolean", "default", false)),
            "required", List.of("pattern"),
            "additionalProperties", false);

    private final ObjectMapper objectMapper;

    public GrepToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String displayName() {
        return "搜索文件内容";
    }

    @Override
    public String description() {
        return "Search UTF-8 workspace files for lines matching a regular expression.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public Validation validate(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            if (node == null || !node.isObject() || node.properties().stream()
                    .anyMatch(entry -> !FIELDS.contains(entry.getKey()))) return rejected();
            JsonNode pattern = node.get("pattern");
            if (pattern == null || !pattern.isTextual() || pattern.asText().isBlank()) return rejected();
            if (node.has("path") && !node.get("path").isTextual()) return rejected();
            if (node.has("include")
                    && (!node.get("include").isTextual() || node.get("include").asText().isBlank())) {
                return rejected();
            }
            if (node.has("force") && !node.get("force").isBoolean()) return rejected();
            return Validation.accepted();
        } catch (Exception exception) {
            return rejected();
        }
    }

    private Validation rejected() {
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "grep 参数无效，未搜索文件");
    }
}
