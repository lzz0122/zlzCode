package com.zlzcode.codeagent.agent.stream;

import com.zlzcode.codeagent.agent.history.AgentHistory;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.openai.model.ChatStreamSignal;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

@Component
public final class AgentFinalAnswerStreamProcessor {

    public Flux<Output> processFinalAnswer(
            Flux<ChatStreamSignal> signals,
            AgentHistory history) {
        FinalAnswerStreamState state = new FinalAnswerStreamState();
        return signals
                .<Output>handle((signal, sink) -> mapSignal(signal, state, sink))
                .concatWith(Mono.defer(() -> finalizeStream(state, history)));
    }

    /*
     * 背景：OpenAI 最终回答流同时包含文本、用量和结束标记，只有文本需要直接发送给前端。
     * 设计意图：集中处理信号到 Agent 事件的转换，并把不会产生事件的协议元数据记录到本次流状态。
     * 关键约束：信号必须按上游顺序处理；Usage 和 Done 不能误发为文本事件，否则会破坏 SSE 协议。
     */
    private void mapSignal(
            ChatStreamSignal signal,
            FinalAnswerStreamState state,
            SynchronousSink<Output> sink) {
        if (signal instanceof ChatStreamSignal.Text text) {
            state.emittedText = true;
            state.text.append(text.value());
            sink.next(new Output.Text(text.value()));
        } else if (signal instanceof ChatStreamSignal.Usage usage) {
            state.inputTokens = usage.inputTokens();
            state.outputTokens = usage.outputTokens();
        } else if (signal instanceof ChatStreamSignal.Done) {
            state.upstreamDone = true;
        }
    }

    /*
     * 背景：模型流结束并不等于最终回答成功，缺少结束标记或正文都可能留下不完整的运行。
     * 设计意图：在所有上游信号处理完后统一校验并返回最终结果，由运行编排层完成持久化和终态事件。
     * 关键约束：必须同时收到 Done 和至少一段文本；否则必须报告协议错误，不能向上游提供可完成结果。
     */
    private Mono<Output> finalizeStream(
            FinalAnswerStreamState state,
            AgentHistory history) {
        if (!state.upstreamDone) {
            return Mono.error(OpenAiIntegrationException.streamBroken());
        }
        if (!state.emittedText) {
            return Mono.error(OpenAiIntegrationException.finalTextMissing());
        }
        String content = state.text.toString();
        history.appendFinalAssistant(content);
        return Mono.just(new Output.Finished(content, state.inputTokens, state.outputTokens));
    }

    public sealed interface Output permits Output.Text, Output.Finished {

        record Text(String value) implements Output {
        }

        record Finished(String content, Integer inputTokens, Integer outputTokens) implements Output {
        }
    }

    private static final class FinalAnswerStreamState {

        private boolean upstreamDone;
        private boolean emittedText;
        private final StringBuilder text = new StringBuilder();
        private Integer inputTokens;
        private Integer outputTokens;
    }
}
