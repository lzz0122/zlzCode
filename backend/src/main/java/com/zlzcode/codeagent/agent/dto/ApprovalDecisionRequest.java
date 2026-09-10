package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @JsonProperty("sessionId")
        @NotBlank @Size(max = 128) String sessionId,
        @NotNull ApprovalRecord.Decision decision) {
}
