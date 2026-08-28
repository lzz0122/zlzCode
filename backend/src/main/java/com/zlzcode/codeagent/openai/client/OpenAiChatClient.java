package com.zlzcode.codeagent.openai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.openai.contract.OpenAiChatContract;
import com.zlzcode.codeagent.openai.exception.OpenAiClientException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiChatClient {

    private final OpenAiTransportClient transportClient;
    private final OpenAiChatContract chatContract;

    public OpenAiChatClient(
            OpenAiTransportClient transportClient,
            OpenAiChatContract chatContract) {
        this.transportClient = transportClient;
        this.chatContract = chatContract;
    }

    public Mono<ToolDecision> decide(AgentRunRequest request) {
        return requestJson(request, chatContract.firstRequest(request))
                .map(chatContract::parseDecision)
                .onErrorMap(OpenAiClientException::fromThrowable);
    }

    public Flux<ChatStreamSignal> stream(AgentRunRequest request) {
        return streamBody(request, chatContract.streamRequest(request));
    }

    public Flux<ChatStreamSignal> streamFinal(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        return streamBody(request, chatContract.finalRequest(request, decision, toolResult));
    }

    private Mono<JsonNode> requestJson(AgentRunRequest request, Map<String, Object> body) {
        return transportClient.postJson(
                request.openai(), "/chat/completions", body,
                Duration.ofSeconds(120), OpenAiClientException::fromStatus);
    }

    private Flux<ChatStreamSignal> streamBody(
            AgentRunRequest request,
            Map<String, Object> body) {
        return transportClient.postEventStream(
                        request.openai(), "/chat/completions", body,
                        Duration.ofSeconds(120), OpenAiClientException::fromStatus)
                .concatMapIterable(chatContract::parseStreamEvent)
                .takeUntil(signal -> signal instanceof ChatStreamSignal.Done)
                .onErrorMap(OpenAiClientException::fromThrowable);
    }
}
