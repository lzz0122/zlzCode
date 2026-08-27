package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConversationMessageInput(
        @NotBlank @Pattern(regexp = "user|assistant") String role,
        @NotBlank @Size(max = 100_000) String content,
        @JsonProperty("toolHistory") @Size(max = 15) List<@Valid ConversationToolHistoryInput> toolHistory) {

    public ConversationMessageInput {
        toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
    }

    @AssertTrue
    public boolean isToolHistoryAllowed() {
        return toolHistory.isEmpty() || "assistant".equals(role);
    }
}
