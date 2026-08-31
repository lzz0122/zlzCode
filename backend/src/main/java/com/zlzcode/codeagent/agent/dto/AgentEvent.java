package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public sealed interface AgentEvent permits AgentEvent.Status, AgentEvent.TextDelta,
        AgentEvent.ToolStarted, AgentEvent.ToolFinished, AgentEvent.Completed, AgentEvent.Error,
        AgentEvent.RunStarted {

    @JsonProperty("type")
    String type();

    record Status(String label) implements AgentEvent {
        @Override
        public String type() {
            return "status";
        }
    }

    record RunStarted(String runId) implements AgentEvent {
        @Override
        public String type() {
            return "run_started";
        }
    }

    record TextDelta(String delta) implements AgentEvent {
        @Override
        public String type() {
            return "text_delta";
        }
    }

    record ToolStarted(String id, String label, String detail) implements AgentEvent {
        @Override
        public String type() {
            return "tool_started";
        }
    }

    record ToolFinished(String id, String state, String detail) implements AgentEvent {
        @Override
        public String type() {
            return "tool_finished";
        }
    }

    record ToolHistory(String name, String arguments, String result) {
    }

    record RunMetrics(
            int steps,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("inputTokens") Integer inputTokens,
            @JsonProperty("outputTokens") Integer outputTokens) {
    }

    record Completed(RunMetrics metrics, @JsonProperty("toolHistory") List<ToolHistory> toolHistory)
            implements AgentEvent {
        @Override
        public String type() {
            return "completed";
        }
    }

    record Error(String message, String code, boolean retryable) implements AgentEvent {
        @Override
        public String type() {
            return "error";
        }
    }
}
