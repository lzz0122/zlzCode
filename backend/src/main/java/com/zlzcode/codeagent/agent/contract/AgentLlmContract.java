package com.zlzcode.codeagent.agent.contract;

/**
 * Agent 与 LLM 之间的语义契约。OpenAI JSON 的具体编码由 OpenAiChatProtocol 负责。
 */
public final class AgentLlmContract {

    private static final String SYSTEM_PROMPT = """
            You are a code workspace assistant.
            You have access to exactly one workspace tool named %s.
            When the user asks about the selected project's structure or files, use that
            tool before making factual claims. It can list only the immediate entries at
            the workspace root. Never call another tool, invent file names, or emit raw
            tool-call protocol text.
            """.trim();

    private AgentLlmContract() {
    }

    public static String systemPrompt(String toolName) {
        return SYSTEM_PROMPT.formatted(toolName);
    }
}
