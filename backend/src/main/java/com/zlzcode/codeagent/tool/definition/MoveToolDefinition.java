package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class MoveToolDefinition implements ToolDefinition {

    private static final String NAME = "move";
    private static final Set<String> FIELDS = Set.of("source", "destination");

    private final ObjectMapper objectMapper;
    private final Map<String, Object> parametersSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1),
                    "destination", Map.of("type", "string", "minLength", 1)),
            "required", List.of("source", "destination"),
            "additionalProperties", false);

    public MoveToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "移动文件";
    }

    @Override
    public String description() {
        return "Move an observed file within the selected workspace after user approval.";
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
            JsonNode source = node.get("source");
            JsonNode destination = node.get("destination");
            if (source == null || !source.isTextual() || source.asText().isBlank()) return rejected();
            if (destination == null || !destination.isTextual() || destination.asText().isBlank()) {
                return rejected();
            }
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
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "move 参数无效，未移动文件");
    }
}
