package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.history.AgentHistory;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmResponse;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.stream.AgentFinalAnswerStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

@Service
public class AgentRunService {

    private final OpenAiChatClient chatClient;
    private final WorkspaceRegistry workspaceRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentRunExceptionMapper exceptionMapper;
    private final AgentFinalAnswerStreamProcessor finalAnswerStreamProcessor;
    private final ConversationHistoryBuilder historyBuilder;
    private final SessionService sessionService;
    private final ToolDefinition workspaceTool;

    public AgentRunService(
            OpenAiChatClient chatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper exceptionMapper,
            AgentFinalAnswerStreamProcessor finalAnswerStreamProcessor,
            ConversationHistoryBuilder historyBuilder,
            SessionService sessionService,
            ToolDefinition workspaceTool) {
        this.chatClient = chatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.exceptionMapper = exceptionMapper;
        this.finalAnswerStreamProcessor = finalAnswerStreamProcessor;
        this.historyBuilder = historyBuilder;
        this.sessionService = sessionService;
        this.workspaceTool = workspaceTool;
    }

    public Flux<AgentEvent> run(AgentRunRequest request) {
        /*
         * 背景：Agent 通过 SSE 向前端持续发送事件，内部异常若直接逃逸会中断 HTTP 响应并暴露实现细节。
         * 设计意图：在服务边界把已知异常映射为稳定的公开错误事件，而不是把堆栈交给 Web 层处理。
         * 关键约束：错误事件必须终止本次运行，且消息中不得包含密钥、本机路径或内部异常堆栈。
         */
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            return Mono.fromCallable(() -> sessionService.beginRun(
                            request.sessionId(), request.runId(), request.prompt()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(run -> runWithSession(request, run, startedAt));
        }).onErrorResume(exceptionMapper::mapException);
    }

    private Flux<AgentEvent> runWithSession(
            AgentRunRequest request,
            SessionService.RunSession run,
            long startedAt) {
        AgentHistory history = historyBuilder.build(
                AgentLlmContract.systemPrompt(workspaceTool.name()),
                run.completedHistory(),
                run.prompt());
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"),
                        new AgentEvent.Status("正在分析")),
                Mono.fromCallable(() -> workspaceRegistry.resolve(run.workspaceId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(workspace -> chatClient.requestInitialResponse(
                                        request.openai(), llmRequest(
                                                request,
                                                history.snapshot(),
                                                List.of(new LlmRequest.AvailableTool(
                                                        workspaceTool.name(),
                                                        workspaceTool.description(),
                                                        workspaceTool.parametersSchema()))))
                                .flatMapMany(response -> handleResponse(
                                        request, run, workspace, response, history, startedAt))));
    }

    private Flux<AgentEvent> handleResponse(
            AgentRunRequest request,
            SessionService.RunSession run,
            AuthorizedWorkspace workspace,
            LlmResponse response,
            AgentHistory history,
            long startedAt) {
        if (!response.hasToolCalls()) {
            return directAnswerFlow(run, response, history, startedAt);
        }
        return toolCallFlow(request, run, workspace, response, history, startedAt);
    }

    private Flux<AgentEvent> directAnswerFlow(
            SessionService.RunSession run,
            LlmResponse response,
            AgentHistory history,
            long startedAt) {
        String content = response.content();
        if (content == null || content.isBlank()) {
            return Flux.error(OpenAiIntegrationException.noDisplayableResponse());
        }
        history.appendFinalAssistant(content);
        return Flux.concat(
                Flux.just(new AgentEvent.TextDelta(content)),
                persistCompleted(run, content, List.of(), startedAt, 1, null, null));
    }

    private Flux<AgentEvent> toolCallFlow(
            AgentRunRequest request,
            SessionService.RunSession run,
            AuthorizedWorkspace workspace,
            LlmResponse response,
            AgentHistory history,
            long startedAt) {
        LlmToolCall call = response.toolCalls().getFirst();
        history.appendAssistantToolCalls(
                response.content(), response.hiddenReasoning(), List.of(call));
        ToolRegistry.RegisteredTool tool = toolRegistry.find(call.name());
        /*
         * 背景：前端根据工具事件的先后顺序创建轨迹卡片、结束执行状态并展示最终回答。
         * 设计意图：由运行流程统一推进工具阶段，而不是让工具执行方法直接生成 Agent 事件流。
         * 关键约束：必须先发送 ToolStarted，再执行工具；ToolFinished 必须先于最终文本。
         */
        return Flux.concat(
                Flux.just(new AgentEvent.ToolStarted(
                        call.id(), tool.displayName(), null)),
                toolRegistry.execute(tool, workspace, call.arguments())
                        .flatMapMany(outcome -> afterToolExecution(
                                request, run, call, outcome, history, startedAt)));
    }

    private Flux<AgentEvent> afterToolExecution(
            AgentRunRequest request,
            SessionService.RunSession run,
            LlmToolCall call,
            ToolOutcome outcome,
            AgentHistory history,
            long startedAt) {
        history.appendToolResult(call.id(), outcome.modelContent());
        return Flux.concat(
                Flux.just(new AgentEvent.ToolFinished(
                        call.id(), outcome.ok() ? "completed" : "failed",
                        outcome.presentation())),
                Flux.just(new AgentEvent.Status("正在整理结果")),
                finalAnswerStreamProcessor.processFinalAnswer(
                                chatClient.requestFinalAnswer(
                                        request.openai(),
                                        llmRequest(request, history.snapshot(), List.of())),
                                history)
                        .concatMap(output -> mapFinalAnswerOutput(
                                output, run, call, outcome, startedAt)));
    }

    private Mono<AgentEvent> mapFinalAnswerOutput(
            AgentFinalAnswerStreamProcessor.Output output,
            SessionService.RunSession run,
            LlmToolCall call,
            ToolOutcome outcome,
            long startedAt) {
        if (output instanceof AgentFinalAnswerStreamProcessor.Output.Text text) {
            return Mono.just(new AgentEvent.TextDelta(text.value()));
        }
        AgentFinalAnswerStreamProcessor.Output.Finished finished =
                (AgentFinalAnswerStreamProcessor.Output.Finished) output;
        List<Session.ToolHistory> persistedTools = List.of(new Session.ToolHistory(
                call.name() == null ? "" : call.name(),
                call.arguments() == null ? "" : call.arguments(),
                outcome.modelContent()));
        List<AgentEvent.ToolHistory> eventTools = List.of(new AgentEvent.ToolHistory(
                call.name() == null ? "" : call.name(),
                call.arguments() == null ? "" : call.arguments(),
                outcome.modelContent()));
        return persistCompleted(
                run,
                finished.content(),
                persistedTools,
                startedAt,
                2,
                finished.inputTokens(),
                finished.outputTokens(),
                eventTools);
    }

    private Mono<AgentEvent> persistCompleted(
            SessionService.RunSession run,
            String assistantContent,
            List<Session.ToolHistory> persistedTools,
            long startedAt,
            int steps,
            Integer inputTokens,
            Integer outputTokens) {
        return persistCompleted(
                run, assistantContent, persistedTools, startedAt, steps,
                inputTokens, outputTokens, List.of());
    }

    /*
     * 背景：前端收到 completed 后会把本轮视为可进入下一轮的稳定历史，不能先于 Session 文件落盘。
     * 设计意图：在 Agent 编排边界等待完整 Turn 原子保存，再创建终态事件；不让流处理器直接宣布成功。
     * 关键约束：持久化失败必须转成 Error 并保留 incomplete Turn，绝不能继续发送 completed。
     */
    private Mono<AgentEvent> persistCompleted(
            SessionService.RunSession run,
            String assistantContent,
            List<Session.ToolHistory> persistedTools,
            long startedAt,
            int steps,
            Integer inputTokens,
            Integer outputTokens,
            List<AgentEvent.ToolHistory> eventTools) {
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        run, assistantContent, persistedTools))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(completed(
                        startedAt, steps, eventTools, inputTokens, outputTokens));
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

    private LlmRequest llmRequest(
            AgentRunRequest request,
            List<LlmMessage> messages,
            List<LlmRequest.AvailableTool> availableTools) {
        return new LlmRequest(
                request.model(), request.reasoningEffort(), messages, availableTools);
    }

}
