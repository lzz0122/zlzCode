package com.zlzcode.agent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.agent.agent.AgentRunService;
import com.zlzcode.agent.contract.AgentEvent;
import com.zlzcode.agent.contract.AgentRunRequest;
import com.zlzcode.agent.contract.OpenAiConnectionInput;
import com.zlzcode.agent.contract.OpenAiModelListResponse;
import com.zlzcode.agent.llm.ModelDiscoveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class OpenAiController {

    private final ModelDiscoveryService modelDiscoveryService;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public OpenAiController(
            ModelDiscoveryService modelDiscoveryService,
            AgentRunService agentRunService,
            ObjectMapper objectMapper) {
        this.modelDiscoveryService = modelDiscoveryService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/api/openai/models", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<OpenAiModelListResponse> listModels(
            @Valid @RequestBody OpenAiConnectionInput connection) {
        connection.normalizedBaseUri();
        connection.normalizedApiKey();
        return modelDiscoveryService.listModels(connection);
    }

    @PostMapping(path = "/api/agent/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> run(
            @Valid @RequestBody AgentRunRequest request) {
        request.openai().normalizedBaseUri();
        request.openai().normalizedApiKey();
        Flux<ServerSentEvent<String>> stream = agentRunService.run(request)
                .map(this::encodeEvent);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    private ServerSentEvent<String> encodeEvent(AgentEvent event) {
        try {
            return ServerSentEvent.builder(objectMapper.writeValueAsString(event))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 Agent 事件", exception);
        }
    }
}
