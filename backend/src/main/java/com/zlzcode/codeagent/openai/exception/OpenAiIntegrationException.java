package com.zlzcode.codeagent.openai.exception;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.concurrent.TimeoutException;

public class OpenAiIntegrationException extends RuntimeException {

    private final String code;
    private final Integer httpStatus;
    private final String safeMessage;
    private final boolean retryable;

    public OpenAiIntegrationException(String code, String safeMessage, boolean retryable) {
        this(code, null, safeMessage, retryable);
    }

    public OpenAiIntegrationException(
            String code, Integer httpStatus, String safeMessage, boolean retryable) {
        super(safeMessage);
        this.code = code;
        this.httpStatus = httpStatus;
        this.safeMessage = safeMessage;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    public static OpenAiIntegrationException invalidToolResponse() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的工具响应", false);
    }

    public static OpenAiIntegrationException invalidStreamResponse() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的流式响应", false);
    }

    public static OpenAiIntegrationException unsupportedToolStream() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了不支持的工具调用流", false);
    }

    public static OpenAiIntegrationException invalidTextDelta() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的文本增量", false);
    }

    public static OpenAiIntegrationException invalidUsage() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的用量信息", false);
    }

    public static OpenAiIntegrationException noDisplayableResponse() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型没有返回可显示文本或工具调用", false);
    }

    public static OpenAiIntegrationException invalidToolCall() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的工具调用", false);
    }

    public static OpenAiIntegrationException streamBroken() {
        return new OpenAiIntegrationException("LLM_STREAM_BROKEN", "OpenAI 流式响应意外中断", true);
    }

    public static OpenAiIntegrationException finalTextMissing() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型没有返回最终文本", false);
    }

    public static OpenAiIntegrationException timeout() {
        return new OpenAiIntegrationException("LLM_TIMEOUT", "OpenAI 请求超时", true);
    }

    public static OpenAiIntegrationException connectionFailed() {
        return new OpenAiIntegrationException("LLM_CONNECTION_FAILED", "无法连接 OpenAI Base URL", true);
    }

    public static OpenAiIntegrationException genericResponse() {
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型返回了无效的响应", false);
    }

    public static OpenAiIntegrationException modelDiscoveryInvalidResponse() {
        return new OpenAiIntegrationException(
                "MODEL_DISCOVERY_FAILED", 502, "模型服务返回的模型列表格式不兼容", false);
    }

    public static OpenAiIntegrationException modelDiscoveryTimeout() {
        return new OpenAiIntegrationException(
                "MODEL_DISCOVERY_FAILED", 504, "获取模型超时", true);
    }

    public static OpenAiIntegrationException modelDiscoveryConnectionFailed() {
        return new OpenAiIntegrationException(
                "MODEL_DISCOVERY_FAILED", 502, "无法连接 OpenAI Base URL", true);
    }

    public static OpenAiIntegrationException fromModelDiscoveryStatus(int status) {
        if (status == 401 || status == 403) {
            return new OpenAiIntegrationException(
                    "MODEL_DISCOVERY_FAILED", 401, "API Key 无效或没有获取模型权限", false);
        }
        if (status == 404) {
            return new OpenAiIntegrationException(
                    "MODEL_DISCOVERY_FAILED", 400, "Base URL 不支持模型列表接口", false);
        }
        if (status == 429) {
            return new OpenAiIntegrationException(
                    "MODEL_DISCOVERY_FAILED", 429, "模型服务请求过于频繁或额度不足", true);
        }
        if (status >= 300 && status < 400) {
            return new OpenAiIntegrationException(
                    "MODEL_DISCOVERY_FAILED", 502, "模型服务返回了不受支持的重定向", true);
        }
        if (status >= 500) {
            return new OpenAiIntegrationException(
                    "MODEL_DISCOVERY_FAILED", 502, "模型服务暂时不可用", true);
        }
        return new OpenAiIntegrationException(
                "MODEL_DISCOVERY_FAILED", 502, "获取模型失败，请检查 Base URL 和 API Key", false);
    }

    public static RuntimeException fromModelDiscoveryThrowable(Throwable error) {
        if (error instanceof OpenAiIntegrationException integrationException) {
            return integrationException;
        }
        if (error instanceof TimeoutException) {
            return modelDiscoveryTimeout();
        }
        if (error instanceof WebClientRequestException) {
            return modelDiscoveryConnectionFailed();
        }
        return modelDiscoveryInvalidResponse();
    }

    public static OpenAiIntegrationException fromStatus(int status) {
        if (status == 401 || status == 403) {
            return new OpenAiIntegrationException("LLM_AUTH_FAILED", "OpenAI API Key 无效或没有访问权限", false);
        }
        if (status == 404) {
            return new OpenAiIntegrationException("LLM_MODEL_NOT_FOUND", "模型不存在或当前 Key 无权访问", false);
        }
        if (status == 429) {
            return new OpenAiIntegrationException("LLM_RATE_LIMITED", "OpenAI 请求过于频繁或额度不足", true);
        }
        if (status == 400) {
            return new OpenAiIntegrationException("LLM_REQUEST_INVALID", "模型服务拒绝了无效请求", false);
        }
        if (status >= 500) {
            return new OpenAiIntegrationException("LLM_UPSTREAM_ERROR", "模型服务暂时不可用", true);
        }
        return new OpenAiIntegrationException("LLM_RESPONSE_INVALID", "模型服务拒绝了当前请求", false);
    }

    public static RuntimeException fromThrowable(Throwable error) {
        if (error instanceof OpenAiIntegrationException clientException) return clientException;
        if (error instanceof TimeoutException) return timeout();
        if (error instanceof WebClientRequestException) return connectionFailed();
        return genericResponse();
    }
}
