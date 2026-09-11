package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class MkdirToolDefinition implements ToolDefinition {

    private static final String NAME = "mkdir";
    private static final Set<String> FIELDS = Set.of("path", "parents");

    private final ObjectMapper objectMapper;
    private final Map<String, Object> parametersSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string", "minLength", 1),
                    "parents", Map.of("type", "boolean", "default", false)),
            "required", List.of("path"),
            "additionalProperties", false);

    public MkdirToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "创建目录";
    }

    @Override
    public String description() {
        return "Create a directory in the selected workspace after user approval.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return parametersSchema;
    }

    @Override
    public Validation validate(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            if (node == null || !node.isObject() || hasUnknownField(node)) return rejected();
            JsonNode path = node.get("path");
            JsonNode parents = node.get("parents");
            if (path == null || !path.isTextual() || path.asText().isBlank()) return rejected();
            if (parents != null && !parents.isBoolean()) return rejected();
            return Validation.accepted();
        } catch (Exception exception) {
            return rejected();
        }
    }

    private boolean hasUnknownField(JsonNode node) {
        return node.fieldNames().hasNext() && !node.properties().stream()
                .allMatch(entry -> FIELDS.contains(entry.getKey()));
    }

    private Validation rejected() {
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "mkdir 参数无效，未创建目录");
    }
}
