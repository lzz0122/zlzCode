package com.zlzcode.codeagent.agent.stream;

import com.zlzcode.codeagent.agent.history.AgentHistory;
import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

@Component
public final class AgentFinalAnswerStreamProcessor {

    public Flux<Output> processFinalAnswer(
            Flux<LlmStreamEvent> events,
            AgentHistory history) {
        FinalAnswerStreamState state = new FinalAnswerStreamState();
        return events
                .<Output>handle((event, sink) -> mapEvent(event, state, sink))
                .concatWith(Mono.defer(() -> finalizeStream(state, history)));
    }

    /*
     * 背景：最终回答流包含正文、隐藏推理、用量和结束原因，只有正文可以发送给前端。
     * 设计意图：集中完成模型事件到 Agent 输出的转换；隐藏推理和结束原因只作为内部协议信息消费。
     * 关键约束：事件必须按上游顺序处理，隐藏推理不得伪装成正文；当前最终回答阶段也不得接受工具调用。
     */
    private void mapEvent(
            LlmStreamEvent event,
            FinalAnswerStreamState state,
            SynchronousSink<Output> sink) {
        if (event instanceof LlmStreamEvent.AssistantTextChunk text) {
            state.emittedText = true;
            state.text.append(text.text());
            sink.next(new Output.Text(text.text()));
        } else if (event instanceof LlmStreamEvent.TokenUsage usage) {
            state.inputTokens = usage.inputTokens();
            state.outputTokens = usage.outputTokens();
        } else if (event instanceof LlmStreamEvent.ToolCallFragment) {
            sink.error(OpenAiIntegrationException.unsupportedToolStream());
        }
    }

    /*
     * 背景：模型流正常结束也可能没有可展示正文，不能因此把空回答保存为成功历史。
     * 设计意图：传输完整性由客户端收口，这里只校验 Agent 最终回答所需的正文并生成完成结果。
     * 关键约束：至少需要一段正文；隐藏推理和结束原因都不能代替最终回答。
     */
    private Mono<Output> finalizeStream(
            FinalAnswerStreamState state,
            AgentHistory history) {
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

        private boolean emittedText;
        private final StringBuilder text = new StringBuilder();
        private Integer inputTokens;
        private Integer outputTokens;
    }
}
