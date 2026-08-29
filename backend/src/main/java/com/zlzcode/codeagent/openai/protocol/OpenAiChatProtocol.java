package com.zlzcode.codeagent.openai.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.tool.definition.WorkspaceOverviewToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions 的协议编解码适配。
 *
 * 请求构造和响应解析共同属于同一条外部协议，放在这里可以让客户端只负责调用，
 * 也避免为每个局部 JSON 方法创建无法独立拥有协议的包装类。
 */
@Component
public class OpenAiChatProtocol {

    private static final String FIELD_MODEL = "model";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_TOOLS = "tools";
    private static final String FIELD_TOOL_CHOICE = "tool_choice";
    private static final String FIELD_STREAM = "stream";
    private static final String FIELD_REASONING_EFFORT = "reasoning_effort";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_REASONING_CONTENT = "reasoning_content";
    private static final String FIELD_TOOL_CALLS = "tool_calls";
    private static final String FIELD_FUNCTION_CALL = "function_call";
    private static final String FIELD_FUNCTION = "function";
    private static final String FIELD_ID = "id";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_ARGUMENTS = "arguments";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_PARAMETERS = "parameters";
    private static final String FIELD_TOOL_CALL_ID = "tool_call_id";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_USAGE = "usage";
    private static final String FIELD_PROMPT_TOKENS = "prompt_tokens";
    private static final String FIELD_COMPLETION_TOKENS = "completion_tokens";
    private static final String FIELD_CHOICES = "choices";
    private static final String FIELD_DELTA = "delta";

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String FUNCTION_KIND = "function";
    private static final String TOOL_CHOICE_AUTO = "auto";
    private static final String STREAM_DONE_MARKER = "[DONE]";

    private final ObjectMapper objectMapper;
    private final ToolDefinition workspaceTool;

    @Autowired
    public OpenAiChatProtocol(
            ObjectMapper objectMapper,
            ToolDefinition workspaceTool) {
        this.objectMapper = objectMapper;
        this.workspaceTool = workspaceTool;
    }

    public OpenAiChatProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new WorkspaceOverviewToolDefinition(objectMapper));
    }

    public Map<String, Object> encodeInitialDecisionRequest(AgentRunRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put(FIELD_MESSAGES, List.of(
                systemMessage(),
                userMessage(request.prompt())));
        body.put(FIELD_TOOLS, List.of(toolDefinition()));
        body.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
        body.put(FIELD_STREAM, false);
        return body;
    }

    public Map<String, Object> encodeDirectAnswerRequest(AgentRunRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put(FIELD_MESSAGES, List.of(userMessage(request.prompt())));
        body.put(FIELD_STREAM, true);
        return body;
    }

    public Map<String, Object> encodeFinalAnswerRequest(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        Map<String, Object> body = baseRequest(request);
        body.put(FIELD_MESSAGES, finalMessages(request, decision, toolResult));
        body.put(FIELD_STREAM, true);
        return body;
    }

    public ToolDecision decodeInitialDecision(JsonNode payload) {
        JsonNode message = singleMessage(payload);
        String content = textOrNull(message.get(FIELD_CONTENT));
        String reasoningContent = textOrNull(message.get(FIELD_REASONING_CONTENT));
        JsonNode calls = message.get(FIELD_TOOL_CALLS);
        if (calls == null || calls.isNull() || (calls.isArray() && calls.isEmpty())) {
            return new ToolDecision(content, reasoningContent, null);
        }
        if (!calls.isArray() || calls.size() != 1) {
            throw OpenAiIntegrationException.invalidToolResponse();
        }

        JsonNode call = calls.get(0);
        JsonNode function = call == null ? null : call.get(FIELD_FUNCTION);
        if (call == null || !call.isObject() || function == null || !function.isObject()) {
            throw OpenAiIntegrationException.invalidToolResponse();
        }

        String id = textOrNull(call.get(FIELD_ID));
        String type = textOrNull(call.get(FIELD_TYPE));
        String name = textOrNull(function.get(FIELD_NAME));
        String arguments = textOrNull(function.get(FIELD_ARGUMENTS));
        if (id == null || id.isBlank() || !FUNCTION_KIND.equals(type) || name == null || name.isBlank()) {
            throw OpenAiIntegrationException.invalidToolResponse();
        }
        return new ToolDecision(
                content,
                reasoningContent,
                new ToolDecision.ToolCall(id, type, name, arguments));
    }

    public List<ChatStreamSignal> decodeStreamEventSignals(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) return List.of();
        if (STREAM_DONE_MARKER.equals(data.trim())) return List.of(new ChatStreamSignal.Done());

        final JsonNode payload;
        try {
            payload = objectMapper.readTree(data);
        } catch (Exception exception) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        if (payload == null || !payload.isObject()) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }

        List<ChatStreamSignal> signals = new ArrayList<>();
        JsonNode usage = payload.get(FIELD_USAGE);
        if (usage != null && !usage.isNull()) {
            signals.add(new ChatStreamSignal.Usage(
                    nonNegativeInteger(usage.get(FIELD_PROMPT_TOKENS)),
                    nonNegativeInteger(usage.get(FIELD_COMPLETION_TOKENS))));
        }

        JsonNode choices = payload.get(FIELD_CHOICES);
        if (choices == null || choices.isNull()) {
            if (signals.isEmpty()) throw OpenAiIntegrationException.invalidStreamResponse();
            return List.copyOf(signals);
        }
        if (!choices.isArray() || choices.size() > 1) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        if (choices.size() == 1) {
            JsonNode delta = choices.get(0).get(FIELD_DELTA);
            if (delta != null && !delta.isNull()) {
                if (!delta.isObject()
                        || (delta.has(FIELD_TOOL_CALLS) && !delta.get(FIELD_TOOL_CALLS).isNull())
                        || (delta.has(FIELD_FUNCTION_CALL) && !delta.get(FIELD_FUNCTION_CALL).isNull())) {
                    throw OpenAiIntegrationException.unsupportedToolStream();
                }
                JsonNode content = delta.get(FIELD_CONTENT);
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) throw OpenAiIntegrationException.invalidTextDelta();
                    if (!content.asText().isEmpty()) signals.add(new ChatStreamSignal.Text(content.asText()));
                }
            }
        }
        return List.copyOf(signals);
    }

    private JsonNode singleMessage(JsonNode payload) {
        if (payload == null || !payload.isObject()) throw OpenAiIntegrationException.invalidToolResponse();
        JsonNode choices = payload.get(FIELD_CHOICES);
        if (choices == null || !choices.isArray() || choices.size() != 1) {
            throw OpenAiIntegrationException.invalidToolResponse();
        }
        JsonNode message = choices.get(0).get(FIELD_MESSAGE);
        if (message == null || !message.isObject()) throw OpenAiIntegrationException.invalidToolResponse();
        return message;
    }

    private Map<String, Object> baseRequest(AgentRunRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_MODEL, request.model().trim());
        if (request.reasoningEffort() != null && !request.reasoningEffort().trim().isEmpty()) {
            body.put(FIELD_REASONING_EFFORT, request.reasoningEffort().trim());
        }
        return body;
    }

    private Map<String, Object> systemMessage() {
        return Map.of(FIELD_ROLE, ROLE_SYSTEM,
                FIELD_CONTENT, AgentLlmContract.systemPrompt(workspaceTool.name()));
    }

    private Map<String, Object> userMessage(String content) {
        return Map.of(FIELD_ROLE, ROLE_USER, FIELD_CONTENT, content);
    }

    private Map<String, Object> toolDefinition() {
        return Map.of(
                FIELD_TYPE, FUNCTION_KIND,
                FIELD_FUNCTION, Map.of(
                        FIELD_NAME, workspaceTool.name(),
                        FIELD_DESCRIPTION, workspaceTool.description(),
                        FIELD_PARAMETERS, workspaceTool.parametersSchema()));
    }

    private List<Map<String, Object>> finalMessages(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        ToolDecision.ToolCall call = decision.toolCall();
        Map<String, Object> function = Map.of(
                FIELD_NAME, call.name(),
                FIELD_ARGUMENTS, call.arguments() == null ? "" : call.arguments());
        Map<String, Object> serializedCall = Map.of(
                FIELD_ID, call.id(),
                FIELD_TYPE, FUNCTION_KIND,
                FIELD_FUNCTION, function);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put(FIELD_ROLE, ROLE_ASSISTANT);
        assistant.put(FIELD_CONTENT, decision.content());
        assistant.put(FIELD_TOOL_CALLS, List.of(serializedCall));
        if (decision.reasoningContent() != null && !decision.reasoningContent().isBlank()) {
            assistant.put(FIELD_REASONING_CONTENT, decision.reasoningContent());
        }
        return List.of(
                systemMessage(),
                userMessage(request.prompt()),
                assistant,
                Map.of(FIELD_ROLE, ROLE_TOOL, FIELD_TOOL_CALL_ID, call.id(), FIELD_CONTENT, toolResult));
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
            throw OpenAiIntegrationException.invalidUsage();
        }
        return node.intValue();
    }
}
