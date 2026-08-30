package com.zlzcode.codeagent.agent.history;

import com.zlzcode.codeagent.agent.dto.ConversationMessageInput;
import com.zlzcode.codeagent.agent.dto.ConversationToolHistoryInput;
import com.zlzcode.codeagent.agent.model.ToolDecision;
import com.zlzcode.codeagent.validation.RequestContractException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class ConversationHistoryBuilder {

    private static final int MAX_MESSAGES = 40;
    private static final int MAX_MESSAGE_CHARS = 100_000;
    private static final int MAX_TOTAL_CHARS = 120_000;
    private static final int MAX_TOOL_HISTORY = 15;
    private static final int MAX_TOOL_NAME_CHARS = 64;
    private static final int MAX_TOOL_VALUE_CHARS = 20_000;
    private static final String FUNCTION_KIND = "function";

    public AgentHistory build(
            String systemPolicy,
            List<ConversationMessageInput> inputs,
            String currentPrompt) {
        if (systemPolicy == null || systemPolicy.isBlank()
                || currentPrompt == null || currentPrompt.isBlank()) {
            throw invalid("Agent 初始消息无效");
        }

        List<ConversationMessageInput> history = inputs == null ? List.of() : inputs;
        validateMessageCount(history);
        List<AgentHistory.Message> messages = new ArrayList<>();
        messages.add(new AgentHistory.TextMessage(AgentHistory.TextRole.SYSTEM, systemPolicy));

        long totalChars = 0L;
        for (int messageIndex = 0; messageIndex < history.size(); messageIndex++) {
            ConversationMessageInput input = history.get(messageIndex);
            String expectedRole = messageIndex % 2 == 0 ? "user" : "assistant";
            if (input == null || !expectedRole.equals(input.role())) {
                throw invalid("会话历史必须按 user 和 assistant 严格成对排列");
            }
            String content = input.content();
            if (content == null || content.isBlank()) {
                throw invalid("会话历史消息正文不能为空");
            }
            if (content.length() > MAX_MESSAGE_CHARS) {
                throw tooLarge("单条会话历史不能超过 " + MAX_MESSAGE_CHARS + " 个字符");
            }
            totalChars += content.length();

            List<ConversationToolHistoryInput> toolHistory = input.toolHistory() == null
                    ? List.of()
                    : input.toolHistory();
            if (!toolHistory.isEmpty() && !"assistant".equals(input.role())) {
                throw invalid("只有助手历史消息可以包含工具记录");
            }
            if (toolHistory.size() > MAX_TOOL_HISTORY) {
                throw tooLarge("单轮历史最多允许 " + MAX_TOOL_HISTORY + " 条工具记录");
            }

            if ("user".equals(input.role())) {
                messages.add(new AgentHistory.TextMessage(AgentHistory.TextRole.USER, content));
            } else {
                /*
                 * 背景：跨 Run 只保存可见助手正文和有界工具摘要，但模型续聊仍需要合法的
                 * assistant tool_calls -> tool -> assistant 消息序列。
                 * 设计意图：在可信的服务端构造合成 call ID，而不是接受客户端提供关联 ID。
                 * 关键约束：每条工具结果必须紧跟对应调用，且合成 ID 只能用于历史重建，不能授权工具执行。
                 */
                for (int toolIndex = 0; toolIndex < toolHistory.size(); toolIndex++) {
                    ConversationToolHistoryInput exchange = toolHistory.get(toolIndex);
                    totalChars += validateToolHistory(exchange);
                    String callId = "history_" + messageIndex + "_" + toolIndex;
                    ToolDecision.ToolCall call = new ToolDecision.ToolCall(
                            callId, FUNCTION_KIND, exchange.name(), exchange.arguments());
                    messages.add(new AgentHistory.AssistantToolCallsMessage(
                            null, null, List.of(call)));
                    messages.add(new AgentHistory.ToolResultMessage(callId, exchange.result()));
                }
                messages.add(new AgentHistory.TextMessage(AgentHistory.TextRole.ASSISTANT, content));
            }

            if (totalChars > MAX_TOTAL_CHARS) {
                throw tooLarge("会话历史总计不能超过 " + MAX_TOTAL_CHARS + " 个字符");
            }
        }

        messages.add(new AgentHistory.TextMessage(AgentHistory.TextRole.USER, currentPrompt));
        return new AgentHistory(messages);
    }

    private void validateMessageCount(List<ConversationMessageInput> history) {
        if (history.size() > MAX_MESSAGES) {
            throw tooLarge("会话历史最多允许 " + MAX_MESSAGES + " 条消息");
        }
        if (history.size() % 2 != 0) {
            throw invalid("会话历史必须由完整的用户和助手消息对组成");
        }
    }

    private long validateToolHistory(ConversationToolHistoryInput exchange) {
        if (exchange == null
                || exchange.name() == null
                || exchange.name().isBlank()
                || exchange.name().length() > MAX_TOOL_NAME_CHARS
                || !exchange.name().matches("^[A-Za-z0-9_-]+$")
                || exchange.arguments() == null
                || exchange.arguments().length() > MAX_TOOL_VALUE_CHARS
                || exchange.result() == null
                || exchange.result().length() > MAX_TOOL_VALUE_CHARS) {
            throw invalid("历史工具记录结构无效");
        }
        return (long) exchange.name().length()
                + exchange.arguments().length()
                + exchange.result().length();
    }

    private RequestContractException invalid(String message) {
        return new RequestContractException("HISTORY_INVALID", message);
    }

    private RequestContractException tooLarge(String message) {
        return new RequestContractException("HISTORY_TOO_LARGE", message);
    }
}
