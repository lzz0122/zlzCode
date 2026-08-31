package com.zlzcode.codeagent.openai.client;

import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.openai.protocol.OpenAiChatProtocol;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OpenAiChatClient {

    private final OpenAiTransportClient transportClient;
    private final OpenAiChatProtocol chatProtocol;

    public OpenAiChatClient(
            OpenAiTransportClient transportClient,
            OpenAiChatProtocol chatProtocol) {
        this.transportClient = transportClient;
        this.chatProtocol = chatProtocol;
    }

    public Flux<LlmStreamEvent> chat(
            OpenAiConnectionInput connection,
            LlmRequest request) {
        AtomicBoolean receivedDone = new AtomicBoolean();
        /*
         * 背景：OpenAI 用 [DONE] 表示 SSE 传输完整结束，它不是模型生成的语义事件。
         * 设计意图：客户端在传输边界验证并消费该标记，只向 Agent 暴露 Provider-neutral 模型事件。
         * 关键约束：上游未发送 [DONE] 就结束时必须报流中断；不能把普通 onComplete 当成完整回答。
         */
        return transportClient.postEventStream(
                        connection, "/chat/completions",
                        chatProtocol.encodeChatRequest(request),
                        Duration.ofSeconds(120), OpenAiIntegrationException::fromStatus)
                .map(chatProtocol::decodeStreamFrame)
                .takeUntil(frame -> {
                    if (frame.done()) receivedDone.set(true);
                    return frame.done();
                })
                .concatMapIterable(OpenAiChatProtocol.DecodedStreamFrame::events)
                .concatWith(Flux.defer(() -> receivedDone.get()
                        ? Flux.empty()
                        : Flux.error(OpenAiIntegrationException.streamBroken())))
                .onErrorMap(OpenAiIntegrationException::fromThrowable);
    }
}
