package com.zlzcode.codeagent.agent.model;

import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;

import java.util.Objects;

/**
 * 已完成提交准入、等待后台执行的不可变 Run 描述。
 *
 * <p>该对象不携带 Session 历史。历史必须在 Run 获得执行权时重新读取，
 * 否则同一 Session 排队的后续 Run 可能使用过期对话上下文。</p>
 */
public record RunExecution(
        String runId,
        String sessionId,
        String prompt,
        RunOptions options) {

    public RunExecution {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Run ID cannot be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Run prompt cannot be blank");
        }
        options = Objects.requireNonNull(options, "Run options cannot be null");
    }

    public record RunOptions(
            String model,
            String reasoningEffort,
            OpenAiConnectionInput openai) {

        public RunOptions {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("Run model cannot be blank");
            }
            openai = Objects.requireNonNull(openai, "Run OpenAI connection cannot be null");
        }
    }
}
