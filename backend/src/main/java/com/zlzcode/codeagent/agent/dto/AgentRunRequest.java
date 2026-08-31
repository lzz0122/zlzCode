package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentRunRequest(
        @JsonProperty("sessionId")
        @NotBlank @Size(max = 128) String sessionId,
        @NotBlank @Size(max = 20_000) String prompt,
        @NotBlank @Size(max = 256) String model,
        @JsonProperty("reasoningEffort") @Size(max = 64) String reasoningEffort,
        @JsonProperty("maxToolCalls") @Min(1) @Max(15) Integer maxToolCalls,
        @NotNull @Valid OpenAiConnectionInput openai) {

    public AgentRunRequest {
        maxToolCalls = maxToolCalls == null ? 5 : maxToolCalls;
    }
}
