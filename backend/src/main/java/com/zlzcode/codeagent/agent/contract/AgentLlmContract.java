package com.zlzcode.codeagent.agent.contract;

/**
 * Agent 与 LLM 之间的语义契约。OpenAI JSON 的具体编码由 OpenAiChatProtocol 负责。
 */
public final class AgentLlmContract {

    /*
     * 背景：系统消息在 Run 开始时生成一次，但后续请求可能因调用额度耗尽而不再提供工具。
     * 设计意图：系统消息只表达通用行为，具体能力取自每轮请求的工具声明，不重复维护名称和 Schema。
     * 关键约束：不能固定声称某工具始终可用或限制单轮调用数量，否则会误导模型使用过期能力并阻碍多调用执行合同。
     */
    private static final String SYSTEM_PROMPT = """
            You are a code workspace assistant.
            Use only the tools supplied in the current request, following their descriptions
            and parameter schemas. Use available tools when you need to verify facts about
            workspace files. Never invent file names, file contents, or tool results.
            When no tools are available, answer using only the existing conversation and prior
            tool results, and explain what you cannot verify.
            Make tool calls through the tool-call protocol; do not include raw tool-call
            protocol text in ordinary replies.
            """.trim();

    private AgentLlmContract() {
    }

    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }
}
