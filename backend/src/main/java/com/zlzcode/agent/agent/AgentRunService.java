package com.zlzcode.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.agent.contract.AgentEvent;
import com.zlzcode.agent.contract.AgentRunRequest;
import com.zlzcode.agent.validation.RequestContractException;
import com.zlzcode.agent.llm.ChatStreamSignal;
import com.zlzcode.agent.llm.OpenAiChatClient;
import com.zlzcode.agent.llm.OpenAiClientException;
import com.zlzcode.agent.llm.ToolDecision;
import com.zlzcode.agent.workspace.authorization.AuthorizedWorkspace;
import com.zlzcode.agent.workspace.authorization.WorkspaceRegistry;
import com.zlzcode.agent.workspace.authorization.WorkspaceRegistryException;
import com.zlzcode.agent.workspace.tool.WorkspaceOverviewService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentRunService {

    private static final String TOOL_NAME = "list_workspace_entries";
    private static final String TOOL_LABEL = "查看工作区根目录";
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(2);

    private final OpenAiChatClient chatClient;
    private final WorkspaceOverviewService workspaceOverviewService;
    private final WorkspaceRegistry workspaceRegistry;
    private final ObjectMapper objectMapper;

    public AgentRunService(
            OpenAiChatClient chatClient,
            WorkspaceOverviewService workspaceOverviewService,
            ObjectMapper objectMapper,
            WorkspaceRegistry workspaceRegistry) {
        this.chatClient = chatClient;
        this.workspaceOverviewService = workspaceOverviewService;
        this.objectMapper = objectMapper;
        this.workspaceRegistry = workspaceRegistry;
    }

    public Flux<AgentEvent> run(AgentRunRequest request) {
        /*
         * 背景：Agent 通过 SSE 向前端持续发送事件，内部异常若直接逃逸会中断 HTTP 响应并暴露实现细节。
         * 设计意图：在服务边界把已知异常映射为稳定的公开错误事件，而不是把堆栈交给 Web 层处理。
         * 关键约束：错误事件必须终止本次运行，且消息中不得包含密钥、本机路径或内部异常堆栈。
         */
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            return Flux.concat(
                            Flux.just(new AgentEvent.Status("正在发送"),
                                    new AgentEvent.Status("正在分析")),
                            Mono.fromCallable(() -> workspaceRegistry.resolve(
                                            request.workspace().id(), request.workspace().path()))
                                    .flatMapMany(workspace -> chatClient.decide(request)
                                            .flatMapMany(decision -> executeDecision(
                                                    request, workspace, decision, startedAt)))
                    )
                    .onErrorResume(OpenAiClientException.class, error -> Flux.just(
                            new AgentEvent.Error(error.safeMessage(), error.code(), error.retryable())))
                    .onErrorResume(WorkspaceRegistryException.class, error -> Flux.just(
                            new AgentEvent.Error(error.getMessage(), error.code(), error.retryable())))
                    .onErrorResume(RequestContractException.class, error -> Flux.just(
                            new AgentEvent.Error(error.getMessage(), "INVALID_REQUEST", false)))
                    .onErrorResume(error -> Flux.just(
                            new AgentEvent.Error("Agent 运行发生内部错误", "INTERNAL_ERROR", false)));
        });
    }

    private Flux<AgentEvent> executeDecision(
            AgentRunRequest request,
            AuthorizedWorkspace workspace,
            ToolDecision decision,
            long startedAt) {
        if (!decision.hasToolCall()) {
            String content = decision.content();
            if (content == null || content.isBlank()) {
                return Flux.error(new OpenAiClientException(
                        "LLM_RESPONSE_INVALID", "模型没有返回可显示文本或工具调用", false));
            }
            return Flux.just(
                    new AgentEvent.TextDelta(content),
                    completed(startedAt, 1, List.of()));
        }

        ToolDecision.ToolCall call = decision.toolCall();
        if (call.id() == null || call.id().isBlank() || !"function".equals(call.type())) {
            return Flux.error(new OpenAiClientException(
                    "LLM_RESPONSE_INVALID", "模型返回了无效的工具调用", false));
        }

        /*
         * 背景：前端根据工具事件的先后顺序创建轨迹卡片、结束执行状态并展示最终回答。
         * 设计意图：显式串联“开始、结束、整理状态、最终文本”，而不是并行合并这些异步事件。
         * 关键约束：该顺序不能调整；ToolFinished 必须先于最终文本，否则前端会留下状态错乱的工具卡。
         */
        return Flux.concat(
                Flux.just(new AgentEvent.ToolStarted(call.id(), TOOL_LABEL, null)),
                executeTool(workspace, call)
                        .flatMapMany(outcome -> Flux.concat(
                                Flux.just(new AgentEvent.ToolFinished(
                                        call.id(), outcome.ok() ? "completed" : "failed",
                                        outcome.presentation())),
                                Flux.just(new AgentEvent.Status("正在整理结果")),
                                streamFinal(request, decision, call, outcome, startedAt))));
    }

    private Mono<WorkspaceOverviewService.Result> executeTool(
            AuthorizedWorkspace workspace,
            ToolDecision.ToolCall call) {
        if (!TOOL_NAME.equals(call.name())) {
            return Mono.just(failure("TOOL_NOT_AVAILABLE", "未执行未知工具"));
        }
        if (!validEmptyArguments(call.arguments())) {
            return Mono.just(failure("TOOL_ARGUMENTS_INVALID", "工具参数无效，未执行工作区访问"));
        }

        /*
         * 背景：工作区扫描使用阻塞式文件系统 API，直接执行会占用 WebFlux 的 Netty 事件线程。
         * 设计意图：把扫描调度到 boundedElastic，并用短超时限制不可控的磁盘或网络目录访问。
         * 关键约束：不得移回事件循环线程，也不得移除超时边界，否则单次慢扫描可能阻塞其他请求。
         */
        return Mono.fromCallable(() -> workspaceOverviewService.list(workspace.root()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TOOL_TIMEOUT)
                .onErrorReturn(failure("TOOL_TIMEOUT", "读取工作区超时"));
    }

    private Flux<AgentEvent> streamFinal(
            AgentRunRequest request,
            ToolDecision decision,
            ToolDecision.ToolCall call,
            WorkspaceOverviewService.Result outcome,
            long startedAt) {
        /*
         * 背景：上游可能在未发送 [DONE]、未产生正文或只发送 usage 时提前结束流。
         * 设计意图：分别记录协议完成、正文和统计信息，再在流结束时统一判定能否发送 completed。
         * 关键约束：缺少 [DONE] 时必须报告 LLM_STREAM_BROKEN，缺少正文时也不得伪造成功完成事件。
         */
        AtomicBoolean upstreamDone = new AtomicBoolean(false);
        AtomicBoolean emittedText = new AtomicBoolean(false);
        AtomicReference<Integer> inputTokens = new AtomicReference<>();
        AtomicReference<Integer> outputTokens = new AtomicReference<>();

        Flux<AgentEvent> body = chatClient.streamFinal(request, decision, outcome.modelContent())
                .<AgentEvent>handle((signal, sink) -> {
                    if (signal instanceof ChatStreamSignal.Text text) {
                        emittedText.set(true);
                        sink.next(new AgentEvent.TextDelta(text.value()));
                    } else if (signal instanceof ChatStreamSignal.Usage usage) {
                        inputTokens.set(usage.inputTokens());
                        outputTokens.set(usage.outputTokens());
                    } else if (signal instanceof ChatStreamSignal.Done) {
                        upstreamDone.set(true);
                    }
                })
                .concatWith(Mono.defer(() -> {
                    if (!upstreamDone.get()) {
                        return Mono.<AgentEvent>error(new OpenAiClientException(
                                "LLM_STREAM_BROKEN", "OpenAI 流式响应意外中断", true));
                    }
                    if (!emittedText.get()) {
                        return Mono.error(new OpenAiClientException(
                                "LLM_RESPONSE_INVALID", "模型没有返回最终文本", false));
                    }
                    return Mono.just(completed(
                            startedAt,
                            2,
                            List.of(new AgentEvent.ToolHistory(
                                    call.name() == null ? "" : call.name(),
                                    call.arguments() == null ? "" : call.arguments(),
                                    outcome.modelContent())),
                            inputTokens.get(),
                            outputTokens.get()));
                }));
        return body;
    }

    private boolean validEmptyArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return node != null && node.isObject() && node.isEmpty();
        } catch (Exception exception) {
            return false;
        }
    }

    private WorkspaceOverviewService.Result failure(String code, String presentation) {
        String content = "{\"ok\":false,\"error\":{\"code\":\""
                + code + "\",\"message\":\"The selected workspace could not be listed safely.\"}}";
        return new WorkspaceOverviewService.Result(false, content, presentation);
    }

    private AgentEvent.Completed completed(long startedAt, int steps, List<AgentEvent.ToolHistory> history) {
        return completed(startedAt, steps, history, null, null);
    }

    private AgentEvent.Completed completed(
            long startedAt,
            int steps,
            List<AgentEvent.ToolHistory> history,
            Integer inputTokens,
            Integer outputTokens) {
        long durationMs = Math.max(1L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return new AgentEvent.Completed(
                new AgentEvent.RunMetrics(steps, durationMs, inputTokens, outputTokens), history);
    }

}
