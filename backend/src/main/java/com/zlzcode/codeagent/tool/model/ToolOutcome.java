package com.zlzcode.codeagent.tool.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record ToolOutcome(boolean ok, String modelContent, String presentation) {

    public static ToolOutcome failure(
            ObjectMapper objectMapper,
            String code,
            String presentation) {
        try {
            String content = objectMapper.writeValueAsString(
                    Map.of("ok", false, "error", Map.of(
                            "code", code,
                            "message", "The selected workspace could not be listed safely.")));
            return new ToolOutcome(false, content, presentation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工具结果", exception);
        }
    }
}
