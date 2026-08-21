package com.zlzcode.agent.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConversationToolHistoryInput(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String name,
        @Size(max = 20_000) String arguments,
        @Size(max = 20_000) String result) {
}
