package com.zlzcode.codeagent.openai.exception;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.concurrent.TimeoutException;

public class OpenAiClientException extends RuntimeException {

    private final String code;
    private final String safeMessage;
    private final boolean retryable;

    public OpenAiClientException(String code, String safeMessage, boolean retryable) {
        super(safeMessage);
        this.code = code;
        this.safeMessage = safeMessage;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    public static OpenAiClientException invalidToolResponse() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的工具响应", false);
    }

    public static OpenAiClientException invalidStreamResponse() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false);
    }

    public static OpenAiClientException unsupportedToolStream() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了不支持的工具调用流", false);
    }

    public static OpenAiClientException invalidTextDelta() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的文本增量", false);
    }

    public static OpenAiClientException invalidUsage() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的用量信息", false);
    }

    public static OpenAiClientException noDisplayableResponse() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型没有返回可显示文本或工具调用", false);
    }

    public static OpenAiClientException invalidToolCall() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的工具调用", false);
    }

    public static OpenAiClientException streamBroken() {
        return new OpenAiClientException("LLM_STREAM_BROKEN", "OpenAI 流式响应意外中断", true);
    }

    public static OpenAiClientException finalTextMissing() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型没有返回最终文本", false);
    }

    public static OpenAiClientException timeout() {
        return new OpenAiClientException("LLM_TIMEOUT", "OpenAI 请求超时", true);
    }

    public static OpenAiClientException connectionFailed() {
        return new OpenAiClientException("LLM_CONNECTION_FAILED", "无法连接 OpenAI Base URL", true);
    }

    public static OpenAiClientException genericResponse() {
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型返回了无效的响应", false);
    }

    public static OpenAiClientException fromStatus(int status) {
        if (status == 401 || status == 403) {
            return new OpenAiClientException("LLM_AUTH_FAILED", "OpenAI API Key 无效或没有访问权限", false);
        }
        if (status == 404) {
            return new OpenAiClientException("LLM_MODEL_NOT_FOUND", "模型不存在或当前 Key 无权访问", false);
        }
        if (status == 429) {
            return new OpenAiClientException("LLM_RATE_LIMITED", "OpenAI 请求过于频繁或额度不足", true);
        }
        if (status == 400) {
            return new OpenAiClientException("LLM_REQUEST_INVALID", "模型服务拒绝了无效请求", false);
        }
        if (status >= 500) {
            return new OpenAiClientException("LLM_UPSTREAM_ERROR", "模型服务暂时不可用", true);
        }
        return new OpenAiClientException("LLM_RESPONSE_INVALID", "模型服务拒绝了当前请求", false);
    }

    public static RuntimeException fromThrowable(Throwable error) {
        if (error instanceof OpenAiClientException clientException) return clientException;
        if (error instanceof TimeoutException) return timeout();
        if (error instanceof WebClientRequestException) return connectionFailed();
        return genericResponse();
    }
}
