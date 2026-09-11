package com.zlzcode.codeagent.agent.context;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 ReAct 执行的临时状态聚合，不跨请求或 Session 复用。
 */
public final class ReActContext {

    private final RunExecution execution;
    private final AuthorizedWorkspace workspace;
    private final long startedAtNanos;
    private final List<LlmMessage> messages;
    private final Set<String> knownCallIds = new HashSet<>();
    private final Set<String> completedCallIds = new HashSet<>();
    private final List<String> outstandingCallIds = new ArrayList<>();
    private final List<ToolExecutionRecord> toolExecutions = new ArrayList<>();
    private int modelSteps;
    private Integer inputTokens;
    private Integer outputTokens;
    private String finalAnswer;

    public ReActContext(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            List<LlmMessage> initialMessages,
            long startedAtNanos) {
        this.execution = Objects.requireNonNull(execution, "Run execution cannot be null");
        this.workspace = Objects.requireNonNull(workspace, "Authorized workspace cannot be null");
        this.startedAtNanos = startedAtNanos;
        if (initialMessages == null || initialMessages.isEmpty()) {
            throw new IllegalArgumentException("Agent run requires initial messages");
        }
        this.messages = new ArrayList<>(initialMessages.size());
        for (LlmMessage message : initialMessages) {
            appendInitial(message);
        }
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial Agent history contains outstanding tool calls");
        }
    }

    public RunExecution execution() {
        return execution;
    }

    public AuthorizedWorkspace workspace() {
        return workspace;
    }

    public List<LlmMessage> messagesSnapshot() {
        return List.copyOf(messages);
    }

    /*
     * 背景：Agent 可能经过多轮模型请求，最终指标必须覆盖整个 Run，而不能只记录最后一轮。
     * 设计意图：在每轮结果收口后统一累积步骤和用量，流程方法只负责根据结果推进状态。
     * 关键约束：该方法必须在处理当前结果前调用一次，不能在工具执行或递归分支中重复计数。
     */
    public void recordModelTurn(LlmTurnResult result) {
        Objects.requireNonNull(result, "LLM turn result cannot be null");
        modelSteps++;
        inputTokens = addUsage(inputTokens, result.inputTokens());
        outputTokens = addUsage(outputTokens, result.outputTokens());
    }

    public void appendAssistantToolCalls(
            String content,
            String reasoningContent,
            List<LlmToolCall> toolCalls) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Previous tool calls are still outstanding");
        }
        LlmMessage.AssistantToolCallsMessage message = new LlmMessage.AssistantToolCallsMessage(
                content, reasoningContent, toolCalls);
        registerToolCalls(message.toolCalls());
        messages.add(message);
    }

    /*
     * 背景：工具结果既要按原 call ID 回灌给下一轮模型，也要在 Run 成功时投影到 Session 和 SSE。
     * 设计意图：在 Context 内一次完成消息状态和唯一工具记录的更新，避免两个输出列表分别维护。
     * 关键约束：只有工具执行返回最终结果后才能调用，且结果必须严格匹配当前批次的模型调用顺序。
     */
    public void recordToolExecution(LlmToolCall call, ToolOutcome outcome) {
        Objects.requireNonNull(call, "LLM tool call cannot be null");
        Objects.requireNonNull(outcome, "Tool outcome cannot be null");
        appendToolResult(call.id(), outcome.modelContent());
        toolExecutions.add(new ToolExecutionRecord(
                call.name(),
                call.arguments() == null ? "" : call.arguments(),
                outcome.modelContent(),
                outcome.ok(),
                outcome.presentation()));
    }

    public void appendFinalAssistant(String content) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot append final text with outstanding tool calls");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Final assistant content cannot be blank");
        }
        finalAnswer = content;
        messages.add(new LlmMessage.TextMessage(LlmMessage.MessageRole.ASSISTANT, content));
    }

    public Completion completion() {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalStateException("Run has no final answer");
        }
        long durationMs = Math.max(1L, java.time.Duration.ofNanos(
                System.nanoTime() - startedAtNanos).toMillis());
        List<Session.ToolHistory> sessionTools = toolExecutions.stream()
                .map(record -> new Session.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        List<AgentEvent.ToolHistory> eventTools = toolExecutions.stream()
                .map(record -> new AgentEvent.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        return new Completion(
                finalAnswer,
                sessionTools,
                eventTools,
                new AgentEvent.RunMetrics(
                        modelSteps, durationMs, inputTokens, outputTokens));
    }

    private void appendToolResult(String callId, String content) {
        LlmMessage.ToolResultMessage message = new LlmMessage.ToolResultMessage(callId, content);
        completeToolCall(message.toolCallId());
        messages.add(message);
    }

    private void appendInitial(LlmMessage message) {
        Objects.requireNonNull(message, "Agent history message cannot be null");
        if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            if (!outstandingCallIds.isEmpty()) {
                throw new IllegalArgumentException("Initial tool-call groups cannot overlap");
            }
            registerToolCalls(assistant.toolCalls());
        } else if (message instanceof LlmMessage.ToolResultMessage result) {
            completeToolCall(result.toolCallId());
        } else if (message instanceof LlmMessage.TextMessage text
                && text.role() == LlmMessage.MessageRole.ASSISTANT
                && !outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial assistant text precedes outstanding tool results");
        }
        messages.add(message);
    }

    private void registerToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call message cannot be empty");
        }
        Set<String> newIds = new HashSet<>();
        for (LlmToolCall toolCall : toolCalls) {
            if (toolCall == null || toolCall.id() == null || toolCall.id().isBlank()) {
                throw new IllegalArgumentException("Tool call ID cannot be blank");
            }
            if (!newIds.add(toolCall.id()) || knownCallIds.contains(toolCall.id())) {
                throw new IllegalStateException("Tool call ID was already used");
            }
        }
        knownCallIds.addAll(newIds);
        outstandingCallIds.addAll(toolCalls.stream().map(LlmToolCall::id).toList());
    }

    private void completeToolCall(String callId) {
        if (completedCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result was already appended");
        }
        if (!knownCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result references an unknown call ID");
        }
        if (outstandingCallIds.isEmpty() || !outstandingCallIds.getFirst().equals(callId)) {
            throw new IllegalStateException("Tool results must follow model tool-call order");
        }
        outstandingCallIds.removeFirst();
        completedCallIds.add(callId);
    }

    private Integer addUsage(Integer current, Integer incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        long total = (long) current + incoming;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public record Completion(
            String finalAnswer,
            List<Session.ToolHistory> sessionToolHistory,
            List<AgentEvent.ToolHistory> toolHistory,
            AgentEvent.RunMetrics metrics) {

        public Completion {
            sessionToolHistory = List.copyOf(sessionToolHistory);
            toolHistory = List.copyOf(toolHistory);
            Objects.requireNonNull(metrics, "Run metrics cannot be null");
        }
    }

    private record ToolExecutionRecord(
            String name,
            String arguments,
            String modelResult,
            boolean success,
            String presentation) {
    }
}
