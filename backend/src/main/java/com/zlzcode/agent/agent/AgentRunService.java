package com.zlzcode.agent.agent;

import com.zlzcode.agent.contract.AgentEvent;
import com.zlzcode.agent.contract.AgentRunRequest;
import com.zlzcode.agent.contract.RequestContractException;
import com.zlzcode.agent.llm.ChatStreamSignal;
import com.zlzcode.agent.llm.OpenAiChatClient;
import com.zlzcode.agent.llm.OpenAiClientException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentRunService {

    private final OpenAiChatClient chatClient;

    public AgentRunService(OpenAiChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Flux<AgentEvent> run(AgentRunRequest request) {
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            AtomicBoolean upstreamDone = new AtomicBoolean(false);
            AtomicReference<Integer> inputTokens = new AtomicReference<>();
            AtomicReference<Integer> outputTokens = new AtomicReference<>();

            Flux<AgentEvent> body = chatClient.stream(request)
                    .<AgentEvent>handle((signal, sink) -> {
                        if (signal instanceof ChatStreamSignal.Text text) {
                            sink.next(new AgentEvent.TextDelta(text.value()));
                        } else if (signal instanceof ChatStreamSignal.Usage usage) {
                            inputTokens.set(usage.inputTokens());
                            outputTokens.set(usage.outputTokens());
                        } else if (signal instanceof ChatStreamSignal.Done) {
                            upstreamDone.set(true);
                        }
                    })
                    .takeUntil(event -> upstreamDone.get())
                    .concatWith(Mono.<AgentEvent>defer(() -> {
                        if (!upstreamDone.get()) {
                            return Mono.<AgentEvent>error(new OpenAiClientException(
                                    "LLM_STREAM_BROKEN", "OpenAI 流式响应意外中断", true));
                        }
                        long durationMs = Math.max(1L,
                                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                        return Mono.just(new AgentEvent.Completed(
                                new AgentEvent.RunMetrics(1, durationMs,
                                        inputTokens.get(), outputTokens.get()),
                                List.of()));
                    }));

            return Flux.concat(
                            Flux.just(new AgentEvent.Status("正在连接 OpenAI"),
                                    new AgentEvent.Status("正在生成回复")),
                            body)
                    .onErrorResume(OpenAiClientException.class, error -> Flux.just(
                            new AgentEvent.Error(error.safeMessage(), error.code(), error.retryable())))
                    .onErrorResume(RequestContractException.class, error -> Flux.just(
                            new AgentEvent.Error(error.getMessage(), "INVALID_REQUEST", false)))
                    .onErrorResume(error -> Flux.just(
                            new AgentEvent.Error("Agent 运行发生内部错误", "INTERNAL_ERROR", false)));
        });
    }
}
