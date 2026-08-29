package com.zlzcode.codeagent.openai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.openai.protocol.OpenAiChatProtocol;
import com.zlzcode.codeagent.openai.exception.OpenAiClientException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
public class OpenAiChatClient {

    private final OpenAiTransportClient transportClient;
    private final OpenAiChatProtocol chatProtocol;

    public OpenAiChatClient(
            OpenAiTransportClient transportClient,
            OpenAiChatProtocol chatProtocol) {
        this.transportClient = transportClient;
        this.chatProtocol = chatProtocol;
    }

    public Mono<ToolDecision> requestInitialDecision(AgentRunRequest request) {
        return transportClient.postJson(
                        request.openai(), "/chat/completions",
                        chatProtocol.encodeInitialDecisionRequest(request),
                        Duration.ofSeconds(120), OpenAiClientException::fromStatus)
                .map(chatProtocol::decodeInitialDecision)
                .onErrorMap(OpenAiClientException::fromThrowable);
    }

    public Flux<ChatStreamSignal> requestDirectAnswer(AgentRunRequest request) {
        return streamSignals(request, chatProtocol.encodeDirectAnswerRequest(request));
    }

    public Flux<ChatStreamSignal> requestFinalAnswer(
            AgentRunRequest request,
            ToolDecision decision,
            String toolResult) {
        return streamSignals(request, chatProtocol.encodeFinalAnswerRequest(request, decision, toolResult));
    }

    private Flux<ChatStreamSignal> streamSignals(
            AgentRunRequest request,
            Map<String, Object> body) {
        return transportClient.postEventStream(
                        request.openai(), "/chat/completions", body,
                        Duration.ofSeconds(120), OpenAiClientException::fromStatus)
                .concatMapIterable(chatProtocol::decodeStreamEventSignals)
                .takeUntil(signal -> signal instanceof ChatStreamSignal.Done)
                .onErrorMap(OpenAiClientException::fromThrowable);
    }
}
