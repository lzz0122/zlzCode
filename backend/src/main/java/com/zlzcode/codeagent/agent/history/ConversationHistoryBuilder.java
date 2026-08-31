package com.zlzcode.codeagent.agent.history;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.validation.RequestContractException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class ConversationHistoryBuilder {

    private static final int MAX_MESSAGES = 40;
    private static final int MAX_MESSAGE_CHARS = 100_000;
    private static final int MAX_TOTAL_CHARS = 120_000;

    public List<LlmMessage> build(
            String systemPolicy,
            List<Session.Turn> completedTurns,
            String currentPrompt) {
        if (systemPolicy == null || systemPolicy.isBlank()
                || currentPrompt == null || currentPrompt.isBlank()) {
            throw invalid("Agent 初始消息无效");
        }

        List<Session.Turn> history = selectHistory(completedTurns);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage.TextMessage(LlmMessage.MessageRole.SYSTEM, systemPolicy));

        for (int turnIndex = 0; turnIndex < history.size(); turnIndex++) {
            Session.Turn turn = history.get(turnIndex);
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.USER, turn.user().content()));
            /*
             * 背景：Session 只持久化跨 Run 所需的工具摘要，但模型续聊仍要求合法的
             * assistant tool_calls -> tool -> assistant 消息序列。
             * 设计意图：由后端根据已校验快照构造关联 ID，不把持久化摘要误当成可再次执行的工具请求。
             * 关键约束：每条工具结果必须紧跟对应调用，合成 ID 只能用于历史重建，不能授权工具执行。
             */
            for (int toolIndex = 0; toolIndex < turn.assistant().toolHistory().size(); toolIndex++) {
                Session.ToolHistory exchange = turn.assistant().toolHistory().get(toolIndex);
                String callId = "history_" + turnIndex + "_" + toolIndex;
                LlmToolCall call = new LlmToolCall(
                        callId, exchange.name(), exchange.arguments());
                messages.add(new LlmMessage.AssistantToolCallsMessage(
                        null, null, List.of(call)));
                messages.add(new LlmMessage.ToolResultMessage(callId, exchange.result()));
            }
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.ASSISTANT, turn.assistant().content()));
        }

        messages.add(new LlmMessage.TextMessage(LlmMessage.MessageRole.USER, currentPrompt));
        return messages;
    }

    private List<Session.Turn> selectHistory(List<Session.Turn> turns) {
        List<Session.Turn> source = turns == null ? List.of() : turns;
        List<Session.Turn> selected = new ArrayList<>();
        long totalChars = 0L;
        for (int index = source.size() - 1; index >= 0; index--) {
            Session.Turn turn = source.get(index);
            if (turn.state() != Session.TurnState.COMPLETED || turn.assistant() == null) {
                continue;
            }
            long turnChars = turn.user().content().length() + turn.assistant().content().length();
            for (Session.ToolHistory tool : turn.assistant().toolHistory()) {
                turnChars += (long) tool.name().length()
                        + tool.arguments().length()
                        + tool.result().length();
            }
            if (turn.user().content().length() > MAX_MESSAGE_CHARS
                    || turn.assistant().content().length() > MAX_MESSAGE_CHARS
                    || selected.size() * 2 + 2 > MAX_MESSAGES
                    || totalChars + turnChars > MAX_TOTAL_CHARS) {
                break;
            }
            selected.addFirst(turn);
            totalChars += turnChars;
        }
        return selected;
    }

    private RequestContractException invalid(String message) {
        return new RequestContractException("HISTORY_INVALID", message);
    }

}
