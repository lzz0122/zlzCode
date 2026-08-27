package com.zlzcode.codeagent.openai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.openai.exception.OpenAiClientException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import com.zlzcode.codeagent.openai.model.ToolDecision;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class OpenAiChatClient {

    private static final String SYSTEM_PROMPT = """
            You are a code workspace assistant.
            You have access to exactly one workspace tool named list_workspace_entries.
            When the user asks about the selected project's structure or files, use that
            tool before making factual claims. It can list only the immediate entries at
            the workspace root. Never call another tool, invent file names, or emit raw
            tool-call protocol text.
            """.trim();

    private final OpenAiTransportClient transportClient;
    private final ObjectMapper objectMapper;

    public OpenAiChatClient(OpenAiTransportClient transportClient, ObjectMapper objectMapper) {
        this.transportClient = transportClient;
        this.objectMapper = objectMapper;
    }

    public Mono<ToolDecision> decide(AgentRunRequest request) {
        return requestJson(request, firstRequestBody(request))
                .map(this::parseDecision)
                .onErrorMap(this::mapError);
    }

    public Flux<ChatStreamSignal> stream(AgentRunRequest request) {
        Map<String, Object> body = baseRequestBody(request);
        body.put("messages", List.of(userMessage(request.prompt())));
        body.put("stream", true);
        return streamBody(request, body);
    }

    public Flux<ChatStreamSignal> streamFinal(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        Map<String, Object> body = baseRequestBody(request);
        body.put("messages", finalMessages(request, decision, toolResult));
        body.put("stream", true);
        return streamBody(request, body);
    }

    private Mono<JsonNode> requestJson(AgentRunRequest request, Map<String, Object> body) {
        return transportClient.postJson(
                request.openai(), "/chat/completions", body,
                Duration.ofSeconds(120), this::statusError);
    }

    private Flux<ChatStreamSignal> streamBody(
            AgentRunRequest request,
            Map<String, Object> body) {
        return transportClient.postEventStream(
                        request.openai(), "/chat/completions", body,
                        Duration.ofSeconds(120), this::statusError)
                .concatMap(this::parseEvent)
                .takeUntil(signal -> signal instanceof ChatStreamSignal.Done)
                .onErrorMap(this::mapError);
    }

    private ToolDecision parseDecision(JsonNode payload) {
        if (payload == null || !payload.isObject()) invalidResponse();
        JsonNode choices = payload.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1) invalidResponse();
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) invalidResponse();

        String content = textOrNull(message.get("content"));
        String reasoningContent = textOrNull(message.get("reasoning_content"));
        JsonNode calls = message.get("tool_calls");
        if (calls == null || calls.isNull() || calls.size() == 0) {
            return new ToolDecision(content, reasoningContent, null);
        }
        if (!calls.isArray() || calls.size() != 1) invalidResponse();
        JsonNode call = calls.get(0);
        JsonNode function = call.get("function");
        if (function == null || !function.isObject()) invalidResponse();
        return new ToolDecision(
                content,
                reasoningContent,
                new ToolDecision.ToolCall(
                        textOrNull(call.get("id")),
                        textOrNull(call.get("type")),
                        textOrNull(function.get("name")),
                        textOrNull(function.get("arguments"))));
    }

    /*
     * 背景：当前阶段只支持一次完整工具决策，流式工具调用会把名称和参数拆成多个增量片段。
     * 设计意图：首轮使用非流式响应一次性取得工具调用，而不是在尚无组装器时拼接不完整片段。
     * 关键约束：实现并验证工具调用分片组装之前，不得把首轮请求改成 stream=true。
     */
    private Map<String, Object> firstRequestBody(AgentRunRequest request) {
        Map<String, Object> body = baseRequestBody(request);
        body.put("messages", List.of(systemMessage(), userMessage(request.prompt())));
        body.put("tools", List.of(workspaceToolSchema()));
        body.put("tool_choice", "auto");
        body.put("stream", false);
        return body;
    }

    private Map<String, Object> baseRequestBody(AgentRunRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().trim());
        if (request.reasoningEffort() != null && !request.reasoningEffort().trim().isEmpty()) {
            body.put("reasoning_effort", request.reasoningEffort().trim());
        }
        return body;
    }

    private Map<String, Object> systemMessage() {
        return Map.of("role", "system", "content", SYSTEM_PROMPT);
    }

    private Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> workspaceToolSchema() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "list_workspace_entries");
        function.put("description", "List the immediate files and directories at the root of the currently selected code workspace. Use this before making claims about the project's top-level structure.");
        function.put("parameters", Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false));
        return Map.of("type", "function", "function", function);
    }

    /*
     * 背景：模型在第二轮生成答案时需要看到自己发出的工具调用，以及与该调用对应的工具结果。
     * 设计意图：原样回放 assistant 工具调用和结果消息，而不是重新构造一个新的调用身份。
     * 关键约束：tool_call_id 必须与首轮返回值完全一致，否则 OpenAI-compatible 服务会拒绝上下文关联。
     */
    private List<Map<String, Object>> finalMessages(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        ToolDecision.ToolCall call = decision.toolCall();
        Map<String, Object> function = Map.of(
                "name", call.name() == null ? "" : call.name(),
                "arguments", call.arguments() == null ? "" : call.arguments());
        Map<String, Object> serializedCall = Map.of(
                "id", call.id() == null ? "" : call.id(),
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
                Map.of("role", "tool", "tool_call_id", call.id() == null ? "" : call.id(),
                        "content", toolResult));
    }

    /*
     * 背景：最终 SSE 可能分别携带正文、usage 和 [DONE]，也可能返回不符合约定的工具调用增量。
     * 设计意图：按事件类型严格解析并把协议数据转换为内部信号，而不是宽松忽略未知结构。
     * 关键约束：当前第二轮只能输出最终文本；再次出现 tool_calls 或 function_call 必须视为协议错误。
     */
    private Flux<ChatStreamSignal> parseEvent(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) return Flux.empty();
        if ("[DONE]".equals(data.trim())) return Flux.just(new ChatStreamSignal.Done());

        final JsonNode payload;
        try {
            payload = objectMapper.readTree(data);
        } catch (Exception exception) {
            return Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false));
        }
        if (payload == null || !payload.isObject()) {
            return Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false));
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
            return signals.isEmpty()
                    ? Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false))
                    : Flux.fromIterable(signals);
        }
        if (!choices.isArray() || choices.size() > 1) {
            return Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false));
        }
        if (choices.size() == 1) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null && !delta.isNull()) {
                if (!delta.isObject()
                        || (delta.has("tool_calls") && !delta.get("tool_calls").isNull())
                        || (delta.has("function_call") && !delta.get("function_call").isNull())) {
                    return Flux.error(new OpenAiClientException(
                            "LLM_RESPONSE_INVALID", "模型返回了不支持的工具调用流", false));
                }
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) {
                        return Flux.error(new OpenAiClientException(
                                "LLM_RESPONSE_INVALID", "模型返回了无效的文本增量", false));
                    }
                    if (!content.asText().isEmpty()) {
                        signals.add(new ChatStreamSignal.Text(content.asText()));
                    }
                }
            }
        }
        return Flux.fromIterable(signals);
    }

    private RuntimeException mapError(Throwable error) {
        if (error instanceof OpenAiClientException clientException) return clientException;
        if (error instanceof TimeoutException) {
            return new OpenAiClientException("LLM_TIMEOUT", "OpenAI 请求超时", true);
        }
        if (error instanceof WebClientRequestException) {
            return new OpenAiClientException("LLM_CONNECTION_FAILED", "无法连接 OpenAI Base URL", true);
        }
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的响应", false);
    }

    private RuntimeException statusError(int status) {
        if (status == 401 || status == 403) {
            return new OpenAiClientException("LLM_AUTH_FAILED", "OpenAI API Key 无效或没有访问权限", false);
        }
        if (status == 404) {
            return new OpenAiClientException("LLM_MODEL_NOT_FOUND", "模型不存在或当前 Key 无权访问", false);
        }
        if (status == 429) {
            return new OpenAiClientException("LLM_RATE_LIMITED", "OpenAI 请求过于频繁或额度不足", true);
        }
        if (status == 400) {
            return new OpenAiClientException("LLM_REQUEST_INVALID", "模型服务拒绝了无效请求", false);
        }
        if (status >= 500) {
            return new OpenAiClientException("LLM_UPSTREAM_ERROR", "模型服务暂时不可用", true);
        }
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型服务拒绝了当前请求", false);
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private void invalidResponse() {
        throw new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的工具响应", false);
    }

    private Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
            throw new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的用量信息", false);
        }
        return node.intValue();
    }
}
