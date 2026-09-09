package com.zlzcode.codeagent.agent.controller;

import com.zlzcode.codeagent.agent.dto.ApprovalDecisionRequest;
import com.zlzcode.codeagent.agent.dto.ApprovalResponse;
import com.zlzcode.codeagent.agent.service.ApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public final class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping(path = "/api/agent/runs/{runId}/approvals/{approvalId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApprovalResponse> query(
            @PathVariable String runId,
            @PathVariable String approvalId,
            @RequestParam @NotBlank @Size(max = 128) String sessionId) {
        return Mono.fromCallable(() -> approvalService.query(sessionId, runId, approvalId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(path = "/api/agent/runs/{runId}/approvals/{approvalId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApprovalResponse> decide(
            @PathVariable String runId,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return Mono.fromCallable(() -> approvalService.decide(
                        request.sessionId(), runId, approvalId, request.decision()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
