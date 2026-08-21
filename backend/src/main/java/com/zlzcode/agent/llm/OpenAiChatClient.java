package com.zlzcode.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.agent.contract.AgentRunRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class OpenAiChatClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiChatClient(WebClient openAiWebClient, ObjectMapper objectMapper) {
        this.webClient = openAiWebClient;
        this.objectMapper = objectMapper;
    }

    public Flux<ChatStreamSignal> stream(AgentRunRequest request) {
        String apiKey = request.openai().normalizedApiKey();
        String completionsUrl = request.openai().normalizedBaseUri() + "/chat/completions";
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model().trim());
        body.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        body.put("stream", true);
        if (request.reasoningEffort() != null && !request.reasoningEffort().trim().isEmpty()) {
            body.put("reasoning_effort", request.reasoningEffort().trim());
        }

        return webClient.post()
                .uri(completionsUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().thenMany(Flux.error(statusError(response.statusCode().value())));
                    }
                    return response.bodyToFlux(SSE_TYPE)
                            .concatMap(this::parseEvent);
                })
                .timeout(Duration.ofSeconds(120))
                .takeUntil(signal -> signal instanceof ChatStreamSignal.Done)
                .onErrorMap(error -> {
                    if (error instanceof OpenAiClientException) {
                        return error;
                    }
                    if (error instanceof TimeoutException) {
                        return new OpenAiClientException("LLM_TIMEOUT", "OpenAI 请求超时", true);
                    }
                    if (error instanceof WebClientRequestException) {
                        return new OpenAiClientException("LLM_CONNECTION_FAILED", "无法连接 OpenAI Base URL", true);
                    }
                    return new OpenAiClientException(
                            "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false);
                });
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

    private Flux<ChatStreamSignal> parseEvent(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) {
            return Flux.empty();
        }
        if ("[DONE]".equals(data.trim())) {
            return Flux.just(new ChatStreamSignal.Done());
        }

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
            Integer inputTokens = nonNegativeInteger(usage.get("prompt_tokens"));
            Integer outputTokens = nonNegativeInteger(usage.get("completion_tokens"));
            signals.add(new ChatStreamSignal.Usage(inputTokens, outputTokens));
        }

        JsonNode choices = payload.get("choices");
        if (choices == null || !choices.isArray()) {
            return Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false));
        }
        if (choices.size() > 1) {
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

    private Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
            throw new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的用量信息", false);
        }
        return node.intValue();
    }
}
