package com.zlzcode.codeagent.openai.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.openai.exception.OpenAiClientException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions 的线协议适配。
 *
 * 请求构造和响应解析共同属于同一条外部协议，放在这里可以让客户端只负责调用，
 * 也避免为每个局部 JSON 方法创建无法独立拥有协议的包装类。
 */
@Component
public class OpenAiChatContract {

    private final ObjectMapper objectMapper;

    public OpenAiChatContract(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> buildInitialDecisionRequest(AgentRunRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put("messages", List.of(
                systemMessage(),
                userMessage(request.prompt())));
        body.put("tools", List.of(toolDefinition()));
        body.put("tool_choice", "auto");
        body.put("stream", false);
        return body;
    }

    public Map<String, Object> buildDirectAnswerRequest(AgentRunRequest request) {
        Map<String, Object> body = baseRequest(request);
        body.put("messages", List.of(userMessage(request.prompt())));
        body.put("stream", true);
        return body;
    }

    public Map<String, Object> buildFinalAnswerRequest(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        Map<String, Object> body = baseRequest(request);
        body.put("messages", finalMessages(request, decision, toolResult));
        body.put("stream", true);
        return body;
    }

    public ToolDecision parseInitialDecision(JsonNode payload) {
        JsonNode message = singleMessage(payload);
        String content = textOrNull(message.get("content"));
        String reasoningContent = textOrNull(message.get("reasoning_content"));
        JsonNode calls = message.get("tool_calls");
        if (calls == null || calls.isNull() || (calls.isArray() && calls.isEmpty())) {
            return new ToolDecision(content, reasoningContent, null);
        }
        if (!calls.isArray() || calls.size() != 1) {
            throw OpenAiClientException.invalidToolResponse();
        }

        JsonNode call = calls.get(0);
        JsonNode function = call == null ? null : call.get("function");
        if (call == null || !call.isObject() || function == null || !function.isObject()) {
            throw OpenAiClientException.invalidToolResponse();
        }

        String id = textOrNull(call.get("id"));
        String type = textOrNull(call.get("type"));
        String name = textOrNull(function.get("name"));
        String arguments = textOrNull(function.get("arguments"));
        if (id == null || id.isBlank() || !"function".equals(type) || name == null || name.isBlank()) {
            throw OpenAiClientException.invalidToolResponse();
        }
        return new ToolDecision(
                content,
                reasoningContent,
                new ToolDecision.ToolCall(id, type, name, arguments));
    }

    public List<ChatStreamSignal> parseStreamEventSignals(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) return List.of();
        if ("[DONE]".equals(data.trim())) return List.of(new ChatStreamSignal.Done());

        final JsonNode payload;
        try {
            payload = objectMapper.readTree(data);
        } catch (Exception exception) {
            throw OpenAiClientException.invalidStreamResponse();
        }
        if (payload == null || !payload.isObject()) {
            throw OpenAiClientException.invalidStreamResponse();
        }

        List<ChatStreamSignal> signals = new ArrayList<>();
        JsonNode usage = payload.get("usage");
        if (usage != null && !usage.isNull()) {
            signals.add(new ChatStreamSignal.Usage(
                    nonNegativeInteger(usage.get("prompt_tokens")),
                    nonNegativeInteger(usage.get("completion_tokens"))));
        }

        JsonNode choices = payload.get("choices");
        if (choices == null || choices.isNull()) {
            if (signals.isEmpty()) throw OpenAiClientException.invalidStreamResponse();
            return List.copyOf(signals);
        }
        if (!choices.isArray() || choices.size() > 1) {
            throw OpenAiClientException.invalidStreamResponse();
        }
        if (choices.size() == 1) {
            JsonNode delta = choices.get(0).get("delta");
            if (delta != null && !delta.isNull()) {
                if (!delta.isObject()
                        || (delta.has("tool_calls") && !delta.get("tool_calls").isNull())
                        || (delta.has("function_call") && !delta.get("function_call").isNull())) {
                    throw OpenAiClientException.unsupportedToolStream();
                }
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) throw OpenAiClientException.invalidTextDelta();
                    if (!content.asText().isEmpty()) signals.add(new ChatStreamSignal.Text(content.asText()));
                }
            }
        }
        return List.copyOf(signals);
    }

    private JsonNode singleMessage(JsonNode payload) {
        if (payload == null || !payload.isObject()) throw OpenAiClientException.invalidToolResponse();
        JsonNode choices = payload.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1) {
            throw OpenAiClientException.invalidToolResponse();
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) throw OpenAiClientException.invalidToolResponse();
        return message;
    }

    private Map<String, Object> baseRequest(AgentRunRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().trim());
        if (request.reasoningEffort() != null && !request.reasoningEffort().trim().isEmpty()) {
            body.put("reasoning_effort", request.reasoningEffort().trim());
        }
        return body;
    }

    private Map<String, Object> systemMessage() {
        return Map.of("role", "system", "content", AgentLlmContract.systemPrompt());
    }

    private Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> toolDefinition() {
        AgentLlmContract.WorkspaceTool tool = AgentLlmContract.workspaceTool();
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parametersSchema()));
    }

    private List<Map<String, Object>> finalMessages(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        ToolDecision.ToolCall call = decision.toolCall();
        Map<String, Object> function = Map.of(
                "name", call.name(),
                "arguments", call.arguments() == null ? "" : call.arguments());
        Map<String, Object> serializedCall = Map.of(
                "id", call.id(),
                "type", "function",
                "function", function);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", decision.content());
        assistant.put("tool_calls", List.of(serializedCall));
        if (decision.reasoningContent() != null && !decision.reasoningContent().isBlank()) {
            assistant.put("reasoning_content", decision.reasoningContent());
        }
        return List.of(
                systemMessage(),
                userMessage(request.prompt()),
                assistant,
                Map.of("role", "tool", "tool_call_id", call.id(), "content", toolResult));
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
            throw OpenAiClientException.invalidUsage();
        }
        return node.intValue();
    }
}
