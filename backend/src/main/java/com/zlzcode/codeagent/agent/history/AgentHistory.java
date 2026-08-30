package com.zlzcode.codeagent.agent.history;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 当前 Agent Run 内只追加的 LLM 消息历史。
 */
public final class AgentHistory {

    private final List<LlmMessage> messages;
    private final Set<String> knownCallIds = new HashSet<>();
    private final Set<String> completedCallIds = new HashSet<>();
    private final List<String> outstandingCallIds = new ArrayList<>();

    AgentHistory(List<LlmMessage> initialMessages) {
        if (initialMessages == null || initialMessages.isEmpty()) {
            throw new IllegalArgumentException("Agent history requires initial messages");
        }
        this.messages = new ArrayList<>(initialMessages.size());
        for (LlmMessage message : initialMessages) {
            appendInitial(message);
        }
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial Agent history contains outstanding tool calls");
        }
    }

    public List<LlmMessage> snapshot() {
        return List.copyOf(messages);
    }

    public void appendAssistantToolCalls(
            String content,
            String reasoningContent,
            List<LlmToolCall> toolCalls) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Previous tool calls are still outstanding");
        }
        LlmMessage.AssistantToolCalls message = new LlmMessage.AssistantToolCalls(
                content, reasoningContent, toolCalls);
        registerToolCalls(message.toolCalls());
        messages.add(message);
    }

    public void appendToolResult(String callId, String content) {
        LlmMessage.ToolResult message = new LlmMessage.ToolResult(callId, content);
        completeToolCall(message.toolCallId());
        messages.add(message);
    }

    public void appendFinalAssistant(String content) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot append final text with outstanding tool calls");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Final assistant content cannot be blank");
        }
        messages.add(new LlmMessage.Text(LlmMessage.Role.ASSISTANT, content));
    }

    private void appendInitial(LlmMessage message) {
        Objects.requireNonNull(message, "Agent history message cannot be null");
        if (message instanceof LlmMessage.AssistantToolCalls assistant) {
            if (!outstandingCallIds.isEmpty()) {
                throw new IllegalArgumentException("Initial tool-call groups cannot overlap");
            }
            registerToolCalls(assistant.toolCalls());
        } else if (message instanceof LlmMessage.ToolResult result) {
            completeToolCall(result.toolCallId());
        } else if (message instanceof LlmMessage.Text text
                && text.role() == LlmMessage.Role.ASSISTANT
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

}
