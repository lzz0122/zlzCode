package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class WriteToolDefinition implements ToolDefinition {

    private static final String NAME = "write";
    private static final Set<String> FIELDS = Set.of("file_path", "content");

    private final ObjectMapper objectMapper;
    private final Map<String, Object> parametersSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                    "file_path", Map.of("type", "string", "minLength", 1),
                    "content", Map.of("type", "string")),
            "required", List.of("file_path", "content"),
            "additionalProperties", false);

    public WriteToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "写入文件";
    }

    @Override
    public String description() {
        return "Create or replace a UTF-8 text file in the selected workspace after user approval.";
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
            JsonNode content = node.get("content");
            if (filePath == null || !filePath.isTextual() || filePath.asText().isBlank()) return rejected();
            if (content == null || !content.isTextual()) return rejected();
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
        return Validation.rejected("TOOL_ARGUMENTS_INVALID", "write 参数无效，未写入文件");
    }
}
