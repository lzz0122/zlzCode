package com.zlzcode.agent.workspace.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceRef(
        @NotBlank @Size(max = 128) String id,
        @NotBlank @Size(max = 256) String name,
        @NotBlank @Size(max = 4096) String path) {
}
