package com.zlzcode.codeagent.agent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.service.AgentRunService;
import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.dto.RunResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class AgentController {

    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public AgentController(AgentRunService agentRunService, ObjectMapper objectMapper) {
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/api/agent/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<RunResponse>> submit(
            @Valid @RequestBody AgentRunRequest request) {
        request.openai().normalizedBaseUri();
        request.openai().normalizedApiKey();
        return Mono.fromCallable(() -> ResponseEntity.accepted().body(agentRunService.submit(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/agent/runs/{runId}")
    public RunResponse read(
            @PathVariable String runId,
            @RequestParam String sessionId) {
        return agentRunService.read(sessionId, runId);
    }

    @GetMapping(path = "/api/agent/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> events(
            @PathVariable String runId,
            @RequestParam String sessionId) {
        Flux<ServerSentEvent<String>> stream = agentRunService.events(sessionId, runId)
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
