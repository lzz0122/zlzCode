package com.zlzcode.codeagent.openai.controller;

import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.openai.dto.OpenAiModelListResponse;
import com.zlzcode.codeagent.openai.service.ModelDiscoveryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ModelController {

    private final ModelDiscoveryService modelDiscoveryService;

    public ModelController(ModelDiscoveryService modelDiscoveryService) {
        this.modelDiscoveryService = modelDiscoveryService;
    }

    @PostMapping(path = "/api/openai/models", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<OpenAiModelListResponse> listModels(
            @Valid @RequestBody OpenAiConnectionInput connection) {
        connection.normalizedBaseUri();
        connection.normalizedApiKey();
        return modelDiscoveryService.listModels(connection);
    }
}
