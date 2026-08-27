package com.zlzcode.codeagent.workspace.controller;

import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.workspace.dto.WorkspacePickerResponse;
import com.zlzcode.codeagent.workspace.dto.WorkspaceRef;
import com.zlzcode.codeagent.workspace.picker.DirectoryPickerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;

@RestController
public class WorkspaceController {

    private final DirectoryPickerService picker;
    private final WorkspaceRegistry registry;

    public WorkspaceController(DirectoryPickerService picker, WorkspaceRegistry registry) {
        this.picker = picker;
        this.registry = registry;
    }

    @PostMapping(path = "/api/workspaces/pick", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<WorkspacePickerResponse> pick() {
        return Mono.fromCallable(picker::pick)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMinutes(10))
                .map(this::register)
                .defaultIfEmpty(WorkspacePickerResponse.cancelledResponse());
    }

    private WorkspacePickerResponse register(Path selected) {
        if (selected == null) return WorkspacePickerResponse.cancelledResponse();
        var workspace = registry.register(selected);
        return WorkspacePickerResponse.selectedResponse(
                new WorkspaceRef(workspace.id(), workspace.name(), workspace.root().toString()));
    }
}
