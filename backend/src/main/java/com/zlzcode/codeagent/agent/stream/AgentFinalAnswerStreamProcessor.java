package com.zlzcode.codeagent.agent.stream;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.history.AgentHistory;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.time.Duration;
import java.util.List;

@Component
public final class AgentFinalAnswerStreamProcessor {

    public Flux<AgentEvent> processFinalAnswer(
            Flux<ChatStreamSignal> signals,
            AgentHistory history,
            ToolDecision.ToolCall call,
            ToolOutcome outcome,
            long startedAt) {
        FinalAnswerStreamState state = new FinalAnswerStreamState();
        return signals
                .<AgentEvent>handle((signal, sink) -> mapSignal(signal, state, sink))
                .concatWith(Mono.defer(() -> finalizeStream(
                        state, history, call, outcome, startedAt)));
    }

    /*
     * 背景：OpenAI 最终回答流同时包含文本、用量和结束标记，只有文本需要直接发送给前端。
     * 设计意图：集中处理信号到 Agent 事件的转换，并把不会产生事件的协议元数据记录到本次流状态。
     * 关键约束：信号必须按上游顺序处理；Usage 和 Done 不能误发为文本事件，否则会破坏 SSE 协议。
     */
    private void mapSignal(
            ChatStreamSignal signal,
            FinalAnswerStreamState state,
            SynchronousSink<AgentEvent> sink) {
        if (signal instanceof ChatStreamSignal.Text text) {
            state.emittedText = true;
            state.text.append(text.value());
            sink.next(new AgentEvent.TextDelta(text.value()));
        } else if (signal instanceof ChatStreamSignal.Usage usage) {
            state.inputTokens = usage.inputTokens();
            state.outputTokens = usage.outputTokens();
        } else if (signal instanceof ChatStreamSignal.Done) {
            state.upstreamDone = true;
        }
    }

    /*
     * 背景：模型流结束并不等于最终回答成功，缺少结束标记或正文都可能留下不完整的运行。
     * 设计意图：在所有上游信号处理完后统一校验并生成 Completed，避免把收口判断散落到流回调中。
     * 关键约束：必须同时收到 Done 和至少一段文本；否则必须报告协议错误，不能伪造成功完成事件。
     */
    private Mono<AgentEvent> finalizeStream(
            FinalAnswerStreamState state,
            AgentHistory history,
            ToolDecision.ToolCall call,
            ToolOutcome outcome,
            long startedAt) {
        if (!state.upstreamDone) {
            return Mono.error(OpenAiIntegrationException.streamBroken());
        }
        if (!state.emittedText) {
            return Mono.error(OpenAiIntegrationException.finalTextMissing());
        }
        history.appendFinalAssistant(state.text.toString());
        long durationMs = Math.max(1L,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return Mono.just(new AgentEvent.Completed(
                new AgentEvent.RunMetrics(
                        2, durationMs, state.inputTokens, state.outputTokens),
                List.of(new AgentEvent.ToolHistory(
                        call.name() == null ? "" : call.name(),
                        call.arguments() == null ? "" : call.arguments(),
                        outcome.modelContent()))));
    }

    private static final class FinalAnswerStreamState {

        private boolean upstreamDone;
        private boolean emittedText;
        private final StringBuilder text = new StringBuilder();
        private Integer inputTokens;
        private Integer outputTokens;
    }
}
