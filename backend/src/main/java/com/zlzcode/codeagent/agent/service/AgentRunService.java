package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.context.AgentRunContext;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class AgentRunService {

    private final OpenAiChatClient chatClient;
    private final WorkspaceRegistry workspaceRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentRunExceptionMapper exceptionMapper;
    private final LlmTurnStreamProcessor turnStreamProcessor;
    private final ConversationHistoryBuilder historyBuilder;
    private final SessionService sessionService;
    private final ToolDefinition workspaceTool;

    public AgentRunService(
            OpenAiChatClient chatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper exceptionMapper,
            LlmTurnStreamProcessor turnStreamProcessor,
            ConversationHistoryBuilder historyBuilder,
            SessionService sessionService,
            ToolDefinition workspaceTool) {
        this.chatClient = chatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.exceptionMapper = exceptionMapper;
        this.turnStreamProcessor = turnStreamProcessor;
        this.historyBuilder = historyBuilder;
        this.sessionService = sessionService;
        this.workspaceTool = workspaceTool;
    }

    //TODO
    public Flux<AgentEvent> run(AgentRunRequest request) {
        /*
         * 背景：Agent 通过 SSE 向前端持续发送事件，内部异常若直接逃逸会中断 HTTP 响应并暴露实现细节。
         * 设计意图：在服务边界把已知异常映射为稳定的公开错误事件，而不是把堆栈交给 Web 层处理。
         * 关键约束：错误事件必须终止本次运行，且消息中不得包含密钥、本机路径或内部异常堆栈。
         */
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            String runId = "run-" + UUID.randomUUID();
            return Mono.fromCallable(() -> sessionService.beginRun(
                            request.sessionId(), runId, request.prompt()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(run -> Flux.concat(
                            Flux.just(new AgentEvent.RunStarted(run.runId())),
                            runWithSession(request, run, startedAt)));
        }).onErrorResume(exceptionMapper::mapException);
    }

    private Flux<AgentEvent> runWithSession(
            AgentRunRequest request,
            SessionService.RunSession run,
            long startedAt) {
        return Flux.concat(
                Flux.just(new AgentEvent.Status("正在发送"),
                        new AgentEvent.Status("正在分析")),
                Mono.fromCallable(() -> workspaceRegistry.resolve(run.workspaceId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(workspace -> {
                            List<LlmMessage> initialMessages = historyBuilder.build(
                                    AgentLlmContract.systemPrompt(workspaceTool.name()),
                                    run.completedHistory(),
                                    run.prompt());
                            AgentRunContext context = new AgentRunContext(
                                    new AgentRunContext.RunConfiguration(
                                            request.model(),
                                            request.reasoningEffort(),
                                            request.maxToolCalls(),
                                            request.openai()),
                                    run,
                                    workspace,
                                    initialMessages,
                                    startedAt);
                            LlmRequest initialRequest = llmRequest(
                                    context,
                                    context.messages(),
                                    List.of(new LlmRequest.ToolDeclaration(
                                            workspaceTool.name(),
                                            workspaceTool.description(),
                                            workspaceTool.parametersSchema())));
                            return turnStreamProcessor.collect(
                                            chatClient.chat(request.openai(), initialRequest))
                                    .flatMapMany(response -> handleResponse(context, response));
                        })
        );
    }

    private Flux<AgentEvent> handleResponse(
            AgentRunContext context,
            LlmTurnResult response) {
        context.recordModelTurn(response);
        if (!response.hasToolCalls()) {
            return directAnswerFlow(context, response);
        }
        return toolCallFlow(context, response);
    }

    private Flux<AgentEvent> directAnswerFlow(
            AgentRunContext context,
            LlmTurnResult response) {
        String content = response.content();
        if (content == null || content.isBlank()) {
            return Flux.error(OpenAiIntegrationException.noDisplayableResponse());
        }
        context.appendFinalAssistant(content);
        return Flux.concat(
                Flux.just(new AgentEvent.TextDelta(content)),
                persistCompleted(context, content));
    }

    private Flux<AgentEvent> toolCallFlow(
            AgentRunContext context,
            LlmTurnResult response) {
        if (response.toolCalls().size() != 1 || !context.canExecuteTool()) {
            return Flux.error(OpenAiIntegrationException.invalidToolCall());
        }
        LlmToolCall call = response.toolCalls().getFirst();
        context.appendAssistantToolCalls(
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
                toolRegistry.execute(tool, context.workspace(), call.arguments())
                        .flatMapMany(outcome -> afterToolExecution(context, call, outcome)));
    }

    private Flux<AgentEvent> afterToolExecution(
            AgentRunContext context,
            LlmToolCall call,
            ToolOutcome outcome) {
        context.recordToolExecution(call, outcome);
        return Flux.concat(
                Flux.just(new AgentEvent.ToolFinished(
                        call.id(), outcome.ok() ? "completed" : "failed",
                        outcome.presentation())),
                Flux.just(new AgentEvent.Status("正在整理结果")),
                turnStreamProcessor.collect(chatClient.chat(
                                context.configuration().openai(),
                                llmRequest(context, context.messages(),
                                        context.canExecuteTool()
                                                ? List.of(new LlmRequest.ToolDeclaration(
                                                workspaceTool.name(),
                                                workspaceTool.description(),
                                                workspaceTool.parametersSchema()))
                                                : List.of())))
                                .flatMapMany(response -> handleResponse(context, response)));
    }

    /*
     * 背景：前端收到 completed 后会把本轮视为可进入下一轮的稳定历史，不能先于 Session 文件落盘。
     * 设计意图：在 Agent 编排边界等待完整 Turn 原子保存，再创建终态事件；不让流处理器直接宣布成功。
     * 关键约束：持久化失败必须转成 Error 并保留 incomplete Turn，绝不能继续发送 completed。
     */
    private Mono<AgentEvent> persistCompleted(
            AgentRunContext context,
            String assistantContent) {
        return Mono.fromRunnable(() -> sessionService.completeRun(
                        context.sessionRun(), assistantContent, context.sessionToolHistory()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(completed(
                        context));
    }

    private AgentEvent.Completed completed(AgentRunContext context) {
        long durationMs = Math.max(1L, Duration.ofNanos(
                System.nanoTime() - context.startedAtNanos()).toMillis());
        List<AgentEvent.ToolHistory> eventTools = context.toolExecutions().stream()
                .map(record -> new AgentEvent.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        return new AgentEvent.Completed(
                new AgentEvent.RunMetrics(
                        context.modelSteps(), durationMs,
                        context.inputTokens(), context.outputTokens()),
                eventTools);
    }

    private LlmRequest llmRequest(
            AgentRunContext context,
            List<LlmMessage> messages,
            List<LlmRequest.ToolDeclaration> availableTools) {
        return new LlmRequest(
                context.configuration().model(),
                context.configuration().reasoningEffort(),
                messages,
                availableTools);
    }

}
