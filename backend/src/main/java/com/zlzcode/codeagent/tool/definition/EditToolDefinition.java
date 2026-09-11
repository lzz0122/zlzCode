package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class EditToolDefinition implements ToolDefinition {

    private static final String NAME = "edit";
    private static final Set<String> FIELDS = Set.of(
            "file_path", "old_string", "new_string", "replace_all");

    private final ObjectMapper objectMapper;
    private final Map<String, Object> parametersSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                    "file_path", Map.of("type", "string", "minLength", 1),
                    "old_string", Map.of("type", "string", "minLength", 1),
                    "new_string", Map.of("type", "string"),
                    "replace_all", Map.of("type", "boolean", "default", false)),
            "required", List.of("file_path", "old_string", "new_string"),
            "additionalProperties", false);

    public EditToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "编辑文件";
    }

    @Override
    public String description() {
        return "Replace exact text in an observed UTF-8 file after user approval.";
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
            JsonNode oldString = node.get("old_string");
            JsonNode newString = node.get("new_string");
            JsonNode replaceAll = node.get("replace_all");
            if (filePath == null || !filePath.isTextual() || filePath.asText().isBlank()) return rejected();
            if (oldString == null || !oldString.isTextual() || oldString.asText().isEmpty()) return rejected();
            if (newString == null || !newString.isTextual()) return rejected();
            if (replaceAll != null && !replaceAll.isBoolean()) return rejected();
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
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "edit 参数无效，未编辑文件");
    }
}
