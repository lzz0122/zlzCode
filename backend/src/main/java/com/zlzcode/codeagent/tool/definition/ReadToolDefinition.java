package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class ReadToolDefinition implements ToolDefinition {

    private static final String NAME = "read";
    private static final Set<String> FIELDS = Set.of("file_path", "offset", "limit");

    private final ObjectMapper objectMapper;
    private final int maxLimit;
    private final Map<String, Object> parametersSchema;

    public ReadToolDefinition(ObjectMapper objectMapper, ToolProperties properties) {
        this.objectMapper = objectMapper;
        this.maxLimit = properties.read().maxLimit();
        this.parametersSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "minLength", 1),
                        "offset", Map.of("type", "integer", "minimum", 1),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", maxLimit)),
                "required", List.of("file_path"),
                "additionalProperties", false);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "读取文件";
    }

    @Override
    public String description() {
        return "Read a line window from a UTF-8 text file in the selected workspace.";
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
            JsonNode filePath = node.get("file_path");
            if (filePath == null || !filePath.isTextual() || filePath.asText().isBlank()) return rejected();
            if (!validPositiveInt(node.get("offset"), Integer.MAX_VALUE)) return rejected();
            if (!validPositiveInt(node.get("limit"), maxLimit)) return rejected();
            return Validation.accepted();
        } catch (Exception exception) {
            return rejected();
        }
    }

    private boolean hasUnknownField(JsonNode node) {
        return node.fieldNames().hasNext() && !node.properties().stream()
                .allMatch(entry -> FIELDS.contains(entry.getKey()));
    }

    private boolean validPositiveInt(JsonNode node, int maximum) {
        return node == null || node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() >= 1
                && node.intValue() <= maximum;
    }

    private Validation rejected() {
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "read 参数无效，未读取文件");
    }
}
