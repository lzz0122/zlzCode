package com.zlzcode.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.agent.contract.OpenAiConnectionInput;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.IntFunction;

@Service
public class OpenAiTransportClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;

    public OpenAiTransportClient(WebClient openAiWebClient) {
        this.webClient = openAiWebClient;
    }

    public Mono<JsonNode> getJson(
            OpenAiConnectionInput connection,
            String path,
            Duration timeout,
            IntFunction<? extends RuntimeException> statusError) {
        return webClient.get()
                .uri(endpoint(connection, path))
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(connection.normalizedApiKey()))
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody()
                                .then(Mono.error(statusError.apply(response.statusCode().value())));
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .timeout(timeout);
    }

    public Mono<JsonNode> postJson(
            OpenAiConnectionInput connection,
            String path,
            Object body,
            Duration timeout,
            IntFunction<? extends RuntimeException> statusError) {
        return webClient.post()
                .uri(endpoint(connection, path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(connection.normalizedApiKey()))
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody()
                                .then(Mono.error(statusError.apply(response.statusCode().value())));
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .timeout(timeout);
    }

    public Flux<ServerSentEvent<String>> postEventStream(
            OpenAiConnectionInput connection,
            String path,
            Object body,
            Duration timeout,
            IntFunction<? extends RuntimeException> statusError) {
        return webClient.post()
                .uri(endpoint(connection, path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> headers.setBearerAuth(connection.normalizedApiKey()))
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody()
                                .thenMany(Flux.error(statusError.apply(response.statusCode().value())));
                    }
                    return response.bodyToFlux(SSE_TYPE);
                })
                .timeout(timeout);
    }

    private String endpoint(OpenAiConnectionInput connection, String path) {
        return connection.normalizedBaseUri() + path;
    }
}
