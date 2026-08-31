package com.zlzcode.codeagent.agent.stream;

import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将一轮 LLM 增量事件收口为完整结果，供 Agent 根据实际结果推进流程。
 */
@Component
public final class LlmTurnStreamProcessor {

    public Mono<LlmTurnResult> collect(Flux<LlmStreamEvent> events) {
        TurnState state = new TurnState();
        return events
                .doOnNext(state::accept)
                .then(Mono.defer(state::finish));
    }

    private static final class TurnState {

        private final StringBuilder content = new StringBuilder();
        private final StringBuilder hiddenReasoning = new StringBuilder();
        private final Map<Integer, ToolCallState> toolCalls = new LinkedHashMap<>();
        private Integer inputTokens;
        private Integer outputTokens;
        private LlmStreamEvent.StopReason stopReason;

        private void accept(LlmStreamEvent event) {
            if (event instanceof LlmStreamEvent.AssistantTextChunk text) {
                content.append(text.text());
            } else if (event instanceof LlmStreamEvent.InternalReasoningChunk reasoning) {
                hiddenReasoning.append(reasoning.text());
            } else if (event instanceof LlmStreamEvent.ToolCallFragment fragment) {
                toolCalls.computeIfAbsent(fragment.toolCallIndex(), ignored -> new ToolCallState())
                        .accept(fragment);
            } else if (event instanceof LlmStreamEvent.TokenUsage usage) {
                if (usage.inputTokens() != null) inputTokens = usage.inputTokens();
                if (usage.outputTokens() != null) outputTokens = usage.outputTokens();
            } else if (event instanceof LlmStreamEvent.GenerationFinished finished) {
                stopReason = finished.stopReason();
            }
        }

        private Mono<LlmTurnResult> finish() {
            if (content.isEmpty() && toolCalls.isEmpty()) {
                return Mono.error(OpenAiIntegrationException.noDisplayableResponse());
            }
            try {
                List<LlmToolCall> calls = toolCalls.values().stream()
                        .map(ToolCallState::toToolCall)
                        .toList();
                return Mono.just(new LlmTurnResult(
                        content.isEmpty() ? null : content.toString(),
                        hiddenReasoning.isEmpty() ? null : hiddenReasoning.toString(),
                        calls, inputTokens, outputTokens, stopReason));
            } catch (IllegalArgumentException exception) {
                return Mono.error(OpenAiIntegrationException.invalidToolCall());
            }
        }
    }

    private static final class ToolCallState {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void accept(LlmStreamEvent.ToolCallFragment fragment) {
            if (fragment.callId() != null) id = merge(id, fragment.callId());
            if (fragment.toolName() != null) name = merge(name, fragment.toolName());
            if (fragment.argumentsFragment() != null) arguments.append(fragment.argumentsFragment());
        }

        private LlmToolCall toToolCall() {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Incomplete LLM tool call");
            }
            return new LlmToolCall(id, name, arguments.toString());
        }

        private String merge(String current, String incoming) {
            if (current != null && !current.equals(incoming)) {
                throw new IllegalArgumentException("Conflicting LLM tool call fragment");
            }
            return incoming;
        }
    }
}
