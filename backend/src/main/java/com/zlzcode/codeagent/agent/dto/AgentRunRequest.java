package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.workspace.dto.WorkspaceRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AgentRunRequest(
        @JsonProperty("runId")
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String runId,
        @JsonProperty("sessionId")
        @NotBlank @Size(max = 128) String sessionId,
        @NotNull @Valid WorkspaceRef workspace,
        @NotBlank @Size(max = 20_000) String prompt,
        @NotBlank @Size(max = 256) String model,
        @JsonProperty("reasoningEffort") @Size(max = 64) String reasoningEffort,
        @JsonProperty("maxToolCalls") @Min(1) @Max(15) Integer maxToolCalls,
        @NotNull @Valid OpenAiConnectionInput openai,
        @Size(max = 40) List<@Valid ConversationMessageInput> history) {

    public AgentRunRequest {
        maxToolCalls = maxToolCalls == null ? 5 : maxToolCalls;
        history = history == null ? List.of() : List.copyOf(history);
    }
}
