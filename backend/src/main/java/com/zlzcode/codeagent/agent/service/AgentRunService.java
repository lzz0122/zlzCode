package com.zlzcode.codeagent.agent.service;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.error.AgentRunExceptionMapper;
import com.zlzcode.codeagent.agent.contract.AgentLlmContract;
import com.zlzcode.codeagent.agent.history.AgentHistory;
import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.agent.stream.AgentFinalAnswerStreamProcessor;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    private final ToolDefinition workspaceTool;

    public AgentRunService(
            OpenAiChatClient chatClient,
            WorkspaceRegistry workspaceRegistry,
            ToolRegistry toolRegistry,
            AgentRunExceptionMapper exceptionMapper,
            AgentFinalAnswerStreamProcessor finalAnswerStreamProcessor,
            ConversationHistoryBuilder historyBuilder,
            ToolDefinition workspaceTool) {
        this.chatClient = chatClient;
        this.workspaceRegistry = workspaceRegistry;
        this.toolRegistry = toolRegistry;
        this.exceptionMapper = exceptionMapper;
        this.finalAnswerStreamProcessor = finalAnswerStreamProcessor;
        this.historyBuilder = historyBuilder;
        this.workspaceTool = workspaceTool;
    }

    public Flux<AgentEvent> run(AgentRunRequest request) {
        AgentHistory history = historyBuilder.build(
                AgentLlmContract.systemPrompt(workspaceTool.name()),
                request.history(),
                request.prompt());
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
                    .flatMapMany(workspace -> chatClient.requestInitialDecision(request, history.snapshot())
                            .flatMapMany(decision -> handleDecision(
                                    request, workspace, decision, history, startedAt)))
                    )
                    .onErrorResume(exceptionMapper::mapException);
        });
    }

    private Flux<AgentEvent> handleDecision(
            AgentRunRequest request,
            AuthorizedWorkspace workspace,
            ToolDecision decision,
            AgentHistory history,
            long startedAt) {
        if (!decision.hasToolCall()) {
            return directAnswerFlow(decision, history, startedAt);
        }
        return toolCallFlow(request, workspace, decision, history, startedAt);
    }

    private Flux<AgentEvent> directAnswerFlow(
            ToolDecision decision,
            AgentHistory history,
            long startedAt) {
        String content = decision.content();
        if (content == null || content.isBlank()) {
            return Flux.error(OpenAiIntegrationException.noDisplayableResponse());
        }
        history.appendFinalAssistant(content);
        return Flux.just(
                new AgentEvent.TextDelta(content),
                completed(startedAt, 1, List.of()));
    }

    private Flux<AgentEvent> toolCallFlow(
            AgentRunRequest request,
            AuthorizedWorkspace workspace,
            ToolDecision decision,
            AgentHistory history,
            long startedAt) {
        ToolDecision.ToolCall call = decision.toolCall();
        history.appendAssistantToolCalls(
                decision.content(), decision.reasoningContent(), List.of(call));
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
                                request, call, outcome, history, startedAt)));
    }

    private Flux<AgentEvent> afterToolExecution(
            AgentRunRequest request,
            ToolDecision.ToolCall call,
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
                                request, history.snapshot()),
                        history,
                        call,
                        outcome,
                        startedAt));
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
