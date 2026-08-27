package com.zlzcode.codeagent.openai.model;

public sealed interface ChatStreamSignal permits ChatStreamSignal.Text,
        ChatStreamSignal.Usage, ChatStreamSignal.Done {

    record Text(String value) implements ChatStreamSignal {
    }

    record Usage(Integer inputTokens, Integer outputTokens) implements ChatStreamSignal {
    }

    record Done() implements ChatStreamSignal {
    }
}
