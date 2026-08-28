package com.zlzcode.codeagent.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Agent 与 LLM 之间的语义契约。OpenAI JSON 的具体编码由 OpenAiChatContract 负责。
 */
public final class AgentLlmContract {

    public static final String WORKSPACE_TOOL_NAME = "list_workspace_entries";

    private static final String SYSTEM_PROMPT = """
            You are a code workspace assistant.
            You have access to exactly one workspace tool named list_workspace_entries.
            When the user asks about the selected project's structure or files, use that
            tool before making factual claims. It can list only the immediate entries at
            the workspace root. Never call another tool, invent file names, or emit raw
            tool-call protocol text.
            """.trim();

    private static final WorkspaceTool WORKSPACE_TOOL = new WorkspaceTool(
            WORKSPACE_TOOL_NAME,
            "List the immediate files and directories at the root of the currently selected code workspace. Use this before making claims about the project's top-level structure.",
            true);

    private AgentLlmContract() {
    }

    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static WorkspaceTool workspaceTool() {
        return WORKSPACE_TOOL;
    }

    public static boolean acceptsEmptyObjectArguments(String arguments, ObjectMapper objectMapper) {
        if (arguments == null || arguments.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return node != null && node.isObject() && node.isEmpty();
        } catch (Exception exception) {
            return false;
        }
    }

    public record WorkspaceTool(String name, String description, boolean requiresEmptyObjectArguments) {

        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", java.util.List.of(),
                    "additionalProperties", false);
        }
    }
}
