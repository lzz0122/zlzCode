package com.zlzcode.codeagent.openai.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmResponse;
import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
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
    private static final String FIELD_FINISH_REASON = "finish_reason";
    private static final String FIELD_INDEX = "index";

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String FUNCTION_KIND = "function";
    private static final String TOOL_CHOICE_AUTO = "auto";
    private static final String STREAM_DONE_MARKER = "[DONE]";

    private final ObjectMapper objectMapper;

    public OpenAiChatProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> encodeInitialResponseRequest(LlmRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put(FIELD_MESSAGES, encodeMessages(request.messages()));
        if (!request.availableTools().isEmpty()) {
            body.put(FIELD_TOOLS, request.availableTools().stream()
                    .map(this::encodeToolDefinition)
                    .toList());
            body.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
        }
        body.put(FIELD_STREAM, false);
        return body;
    }

    public Map<String, Object> encodeFinalAnswerRequest(LlmRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put(FIELD_MESSAGES, encodeMessages(request.messages()));
        body.put(FIELD_STREAM, true);
        return body;
    }

    public LlmResponse decodeResponse(JsonNode payload) {
        JsonNode message = singleMessage(payload);
        String content = textOrNull(message.get(FIELD_CONTENT));
        String hiddenReasoning = textOrNull(message.get(FIELD_REASONING_CONTENT));
        JsonNode calls = message.get(FIELD_TOOL_CALLS);
        if (calls == null || calls.isNull() || (calls.isArray() && calls.isEmpty())) {
            return new LlmResponse(content, hiddenReasoning, List.of());
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
        return new LlmResponse(
                content, hiddenReasoning, List.of(new LlmToolCall(id, name, arguments)));
    }

    public DecodedStreamFrame decodeStreamFrame(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) return new DecodedStreamFrame(List.of(), false);
        if (STREAM_DONE_MARKER.equals(data.trim())) {
            return new DecodedStreamFrame(List.of(), true);
        }

        final JsonNode payload;
        try {
            payload = objectMapper.readTree(data);
        } catch (Exception exception) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        if (payload == null || !payload.isObject()) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }

        List<LlmStreamEvent> events = new ArrayList<>();
        JsonNode usage = payload.get(FIELD_USAGE);
        if (usage != null && !usage.isNull()) {
            events.add(new LlmStreamEvent.Usage(
                    nonNegativeInteger(usage.get(FIELD_PROMPT_TOKENS)),
                    nonNegativeInteger(usage.get(FIELD_COMPLETION_TOKENS))));
        }

        JsonNode choices = payload.get(FIELD_CHOICES);
        if (choices == null || choices.isNull()) {
            if (events.isEmpty()) throw OpenAiIntegrationException.invalidStreamResponse();
            return new DecodedStreamFrame(events, false);
        }
        if (!choices.isArray() || choices.size() > 1) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        if (choices.size() == 1) {
            JsonNode choice = choices.get(0);
            if (choice == null || !choice.isObject()) {
                throw OpenAiIntegrationException.invalidStreamResponse();
            }
            JsonNode delta = choice.get(FIELD_DELTA);
            if (delta != null && !delta.isNull()) {
                if (!delta.isObject()) {
                    throw OpenAiIntegrationException.invalidStreamResponse();
                }
                if (delta.has(FIELD_FUNCTION_CALL) && !delta.get(FIELD_FUNCTION_CALL).isNull()) {
                    throw OpenAiIntegrationException.unsupportedToolStream();
                }
                JsonNode content = delta.get(FIELD_CONTENT);
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) throw OpenAiIntegrationException.invalidTextDelta();
                    if (!content.asText().isEmpty()) {
                        events.add(new LlmStreamEvent.TextDelta(content.asText()));
                    }
                }
                JsonNode reasoning = delta.get(FIELD_REASONING_CONTENT);
                if (reasoning != null && !reasoning.isNull()) {
                    if (!reasoning.isTextual()) throw OpenAiIntegrationException.invalidTextDelta();
                    if (!reasoning.asText().isEmpty()) {
                        events.add(new LlmStreamEvent.HiddenReasoningDelta(reasoning.asText()));
                    }
                }
                JsonNode toolCalls = delta.get(FIELD_TOOL_CALLS);
                if (toolCalls != null && !toolCalls.isNull()) {
                    if (!toolCalls.isArray()) {
                        throw OpenAiIntegrationException.invalidStreamResponse();
                    }
                    for (JsonNode toolCall : toolCalls) {
                        events.add(decodeToolCallDelta(toolCall));
                    }
                }
            }
            JsonNode finishReason = choice.get(FIELD_FINISH_REASON);
            if (finishReason != null && !finishReason.isNull()) {
                if (!finishReason.isTextual()) {
                    throw OpenAiIntegrationException.invalidStreamResponse();
                }
                events.add(new LlmStreamEvent.Finish(mapFinishReason(finishReason.asText())));
            }
        }
        return new DecodedStreamFrame(events, false);
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

    private Map<String, Object> baseRequest(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_MODEL, request.model());
        if (request.reasoningEffort() != null) {
            body.put(FIELD_REASONING_EFFORT, request.reasoningEffort());
        }
        return body;
    }

    private Map<String, Object> encodeToolDefinition(LlmRequest.AvailableTool tool) {
        return Map.of(
                FIELD_TYPE, FUNCTION_KIND,
                FIELD_FUNCTION, Map.of(
                        FIELD_NAME, tool.name(),
                        FIELD_DESCRIPTION, tool.description(),
                        FIELD_PARAMETERS, tool.parametersSchema()));
    }

    private List<Map<String, Object>> encodeMessages(List<LlmMessage> messages) {
        return messages.stream().map(this::encodeMessage).toList();
    }

    private Map<String, Object> encodeMessage(LlmMessage message) {
        if (message instanceof LlmMessage.Text text) {
            return Map.of(
                    FIELD_ROLE, switch (text.role()) {
                        case SYSTEM -> ROLE_SYSTEM;
                        case USER -> ROLE_USER;
                        case ASSISTANT -> ROLE_ASSISTANT;
                    },
                    FIELD_CONTENT, text.content());
        }
        if (message instanceof LlmMessage.ToolResult result) {
            return Map.of(
                    FIELD_ROLE, ROLE_TOOL,
                    FIELD_TOOL_CALL_ID, result.toolCallId(),
                    FIELD_CONTENT, result.content());
        }
        if (message instanceof LlmMessage.AssistantToolCalls assistant) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put(FIELD_ROLE, ROLE_ASSISTANT);
            encoded.put(FIELD_CONTENT, assistant.content());
            encoded.put(FIELD_TOOL_CALLS, assistant.toolCalls().stream()
                    .map(this::encodeToolCall)
                    .toList());
            if (assistant.hiddenReasoning() != null && !assistant.hiddenReasoning().isBlank()) {
                encoded.put(FIELD_REASONING_CONTENT, assistant.hiddenReasoning());
            }
            return encoded;
        }
        throw new IllegalArgumentException("Unsupported LLM message: " + message.getClass().getName());
    }

    private Map<String, Object> encodeToolCall(LlmToolCall call) {
        return Map.of(
                FIELD_ID, call.id(),
                FIELD_TYPE, FUNCTION_KIND,
                FIELD_FUNCTION, Map.of(
                        FIELD_NAME, call.name(),
                        FIELD_ARGUMENTS, call.arguments() == null ? "" : call.arguments()));
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

    private LlmStreamEvent.ToolCallDelta decodeToolCallDelta(JsonNode call) {
        if (call == null || !call.isObject()) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        JsonNode indexNode = call.get(FIELD_INDEX);
        Integer index = nonNegativeInteger(indexNode);
        if (index == null) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        String id = optionalStreamText(call.get(FIELD_ID));
        String type = optionalStreamText(call.get(FIELD_TYPE));
        if (type != null && !FUNCTION_KIND.equals(type)) {
            throw OpenAiIntegrationException.unsupportedToolStream();
        }
        JsonNode function = call.get(FIELD_FUNCTION);
        if (function != null && !function.isNull() && !function.isObject()) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        String name = function == null || function.isNull()
                ? null : optionalStreamText(function.get(FIELD_NAME));
        String arguments = function == null || function.isNull()
                ? null : optionalStreamText(function.get(FIELD_ARGUMENTS));
        if (id == null && name == null && arguments == null) {
            throw OpenAiIntegrationException.invalidStreamResponse();
        }
        return new LlmStreamEvent.ToolCallDelta(index, id, name, arguments);
    }

    private String optionalStreamText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw OpenAiIntegrationException.invalidStreamResponse();
        return node.asText();
    }

    private LlmStreamEvent.Finish.Reason mapFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> LlmStreamEvent.Finish.Reason.STOP;
            case "tool_calls" -> LlmStreamEvent.Finish.Reason.TOOL_CALLS;
            case "length" -> LlmStreamEvent.Finish.Reason.LENGTH;
            case "content_filter" -> LlmStreamEvent.Finish.Reason.CONTENT_FILTER;
            default -> LlmStreamEvent.Finish.Reason.OTHER;
        };
    }

    public record DecodedStreamFrame(List<LlmStreamEvent> events, boolean done) {

        public DecodedStreamFrame {
            events = List.copyOf(events);
        }
    }
}
