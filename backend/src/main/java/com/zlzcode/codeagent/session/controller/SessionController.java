package com.zlzcode.codeagent.session.controller;

import com.zlzcode.codeagent.session.dto.CreateSessionRequest;
import com.zlzcode.codeagent.session.dto.SessionResponse;
import com.zlzcode.codeagent.session.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public final class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SessionResponse.from(sessionService.create(request.workspaceId())));
    }

    @GetMapping("/{sessionId}")
    public SessionResponse read(@PathVariable String sessionId) {
        return SessionResponse.from(sessionService.read(sessionId));
    }
}
