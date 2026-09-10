package com.zlzcode.codeagent.openai.exception;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.concurrent.TimeoutException;

public class OpenAiIntegrationException extends RuntimeException {

    private static final String CODE_RESPONSE_INVALID = "LLM_RESPONSE_INVALID";
    private static final String CODE_MULTIPLE_TOOL_CALLS_UNSUPPORTED =
            "LLM_MULTIPLE_TOOL_CALLS_UNSUPPORTED";
    private static final String CODE_STREAM_BROKEN = "LLM_STREAM_BROKEN";
    private static final String CODE_TIMEOUT = "LLM_TIMEOUT";
    private static final String CODE_CONNECTION_FAILED = "LLM_CONNECTION_FAILED";
    private static final String CODE_AUTH_FAILED = "LLM_AUTH_FAILED";
    private static final String CODE_MODEL_NOT_FOUND = "LLM_MODEL_NOT_FOUND";
    private static final String CODE_RATE_LIMITED = "LLM_RATE_LIMITED";
    private static final String CODE_REQUEST_INVALID = "LLM_REQUEST_INVALID";
    private static final String CODE_UPSTREAM_ERROR = "LLM_UPSTREAM_ERROR";
    private static final String CODE_MODEL_DISCOVERY_FAILED = "MODEL_DISCOVERY_FAILED";

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
        return error(ErrorDefinition.INVALID_TOOL_RESPONSE);
    }

    public static OpenAiIntegrationException invalidStreamResponse() {
        return error(ErrorDefinition.INVALID_STREAM_RESPONSE);
    }

    public static OpenAiIntegrationException unsupportedToolStream() {
        return error(ErrorDefinition.UNSUPPORTED_TOOL_STREAM);
    }

    public static OpenAiIntegrationException invalidTextDelta() {
        return error(ErrorDefinition.INVALID_TEXT_DELTA);
    }

    public static OpenAiIntegrationException invalidUsage() {
        return error(ErrorDefinition.INVALID_USAGE);
    }

    public static OpenAiIntegrationException noDisplayableResponse() {
        return error(ErrorDefinition.NO_DISPLAYABLE_RESPONSE);
    }

    public static OpenAiIntegrationException invalidToolCall() {
        return error(ErrorDefinition.INVALID_TOOL_CALL);
    }

    public static OpenAiIntegrationException multipleToolCallsUnsupported() {
        return error(ErrorDefinition.MULTIPLE_TOOL_CALLS_UNSUPPORTED);
    }

    public static OpenAiIntegrationException streamBroken() {
        return error(ErrorDefinition.STREAM_BROKEN);
    }

    public static OpenAiIntegrationException finalTextMissing() {
        return error(ErrorDefinition.FINAL_TEXT_MISSING);
    }

    public static OpenAiIntegrationException timeout() {
        return error(ErrorDefinition.TIMEOUT);
    }

    public static OpenAiIntegrationException connectionFailed() {
        return error(ErrorDefinition.CONNECTION_FAILED);
    }

    public static OpenAiIntegrationException genericResponse() {
        return error(ErrorDefinition.GENERIC_RESPONSE);
    }

    public static OpenAiIntegrationException modelDiscoveryInvalidResponse() {
        return error(ErrorDefinition.MODEL_DISCOVERY_INVALID_RESPONSE);
    }

    public static OpenAiIntegrationException modelDiscoveryTimeout() {
        return error(ErrorDefinition.MODEL_DISCOVERY_TIMEOUT);
    }

    public static OpenAiIntegrationException modelDiscoveryConnectionFailed() {
        return error(ErrorDefinition.MODEL_DISCOVERY_CONNECTION_FAILED);
    }

    public static OpenAiIntegrationException fromModelDiscoveryStatus(int status) {
        if (status == 401 || status == 403) {
            return error(ErrorDefinition.MODEL_DISCOVERY_AUTH_FAILED);
        }
        if (status == 404) {
            return error(ErrorDefinition.MODEL_DISCOVERY_UNSUPPORTED);
        }
        if (status == 429) {
            return error(ErrorDefinition.MODEL_DISCOVERY_RATE_LIMITED);
        }
        if (status >= 300 && status < 400) {
            return error(ErrorDefinition.MODEL_DISCOVERY_REDIRECT);
        }
        if (status >= 500) {
            return error(ErrorDefinition.MODEL_DISCOVERY_UPSTREAM_ERROR);
        }
        return error(ErrorDefinition.MODEL_DISCOVERY_GENERIC);
    }

    public static OpenAiIntegrationException fromModelDiscoveryThrowable(Throwable error) {
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
            return error(ErrorDefinition.AUTH_FAILED);
        }
        if (status == 404) {
            return error(ErrorDefinition.MODEL_NOT_FOUND);
        }
        if (status == 429) {
            return error(ErrorDefinition.RATE_LIMITED);
        }
        if (status == 400) {
            return error(ErrorDefinition.REQUEST_INVALID);
        }
        if (status >= 500) {
            return error(ErrorDefinition.UPSTREAM_ERROR);
        }
        return error(ErrorDefinition.RESPONSE_REJECTED);
    }

    public static RuntimeException fromThrowable(Throwable error) {
        if (error instanceof OpenAiIntegrationException clientException) return clientException;
        if (error instanceof TimeoutException) return timeout();
        if (error instanceof WebClientRequestException) return connectionFailed();
        return genericResponse();
    }

    private static OpenAiIntegrationException error(ErrorDefinition definition) {
        return new OpenAiIntegrationException(
                definition.code(), definition.httpStatus(), definition.safeMessage(), definition.retryable());
    }

    private enum ErrorDefinition {
        INVALID_TOOL_RESPONSE(CODE_RESPONSE_INVALID, null, "模型返回了无效的工具响应", false),
        INVALID_STREAM_RESPONSE(CODE_RESPONSE_INVALID, null, "模型返回了无效的流式响应", false),
        UNSUPPORTED_TOOL_STREAM(CODE_RESPONSE_INVALID, null, "模型返回了不支持的工具调用流", false),
        INVALID_TEXT_DELTA(CODE_RESPONSE_INVALID, null, "模型返回了无效的文本增量", false),
        INVALID_USAGE(CODE_RESPONSE_INVALID, null, "模型返回了无效的用量信息", false),
        NO_DISPLAYABLE_RESPONSE(CODE_RESPONSE_INVALID, null, "模型没有返回可显示文本或工具调用", false),
        INVALID_TOOL_CALL(CODE_RESPONSE_INVALID, null, "模型返回了无效的工具调用", false),
        MULTIPLE_TOOL_CALLS_UNSUPPORTED(
                CODE_MULTIPLE_TOOL_CALLS_UNSUPPORTED,
                null,
                "当前版本暂不支持模型在同一轮调用多个工具",
                false),
        STREAM_BROKEN(CODE_STREAM_BROKEN, null, "OpenAI 流式响应意外中断", true),
        FINAL_TEXT_MISSING(CODE_RESPONSE_INVALID, null, "模型没有返回最终文本", false),
        TIMEOUT(CODE_TIMEOUT, null, "OpenAI 请求超时", true),
        CONNECTION_FAILED(CODE_CONNECTION_FAILED, null, "无法连接 OpenAI Base URL", true),
        GENERIC_RESPONSE(CODE_RESPONSE_INVALID, null, "模型返回了无效的响应", false),
        MODEL_DISCOVERY_INVALID_RESPONSE(
                CODE_MODEL_DISCOVERY_FAILED, 502, "模型服务返回的模型列表格式不兼容", false),
        MODEL_DISCOVERY_TIMEOUT(CODE_MODEL_DISCOVERY_FAILED, 504, "获取模型超时", true),
        MODEL_DISCOVERY_CONNECTION_FAILED(
                CODE_MODEL_DISCOVERY_FAILED, 502, "无法连接 OpenAI Base URL", true),
        MODEL_DISCOVERY_AUTH_FAILED(
                CODE_MODEL_DISCOVERY_FAILED, 401, "API Key 无效或没有获取模型权限", false),
        MODEL_DISCOVERY_UNSUPPORTED(
                CODE_MODEL_DISCOVERY_FAILED, 400, "Base URL 不支持模型列表接口", false),
        MODEL_DISCOVERY_RATE_LIMITED(
                CODE_MODEL_DISCOVERY_FAILED, 429, "模型服务请求过于频繁或额度不足", true),
        MODEL_DISCOVERY_REDIRECT(
                CODE_MODEL_DISCOVERY_FAILED, 502, "模型服务返回了不受支持的重定向", true),
        MODEL_DISCOVERY_UPSTREAM_ERROR(
                CODE_MODEL_DISCOVERY_FAILED, 502, "模型服务暂时不可用", true),
        MODEL_DISCOVERY_GENERIC(
                CODE_MODEL_DISCOVERY_FAILED, 502, "获取模型失败，请检查 Base URL 和 API Key", false),
        AUTH_FAILED(CODE_AUTH_FAILED, null, "OpenAI API Key 无效或没有访问权限", false),
        MODEL_NOT_FOUND(CODE_MODEL_NOT_FOUND, null, "模型不存在或当前 Key 无权访问", false),
        RATE_LIMITED(CODE_RATE_LIMITED, null, "OpenAI 请求过于频繁或额度不足", true),
        REQUEST_INVALID(CODE_REQUEST_INVALID, null, "模型服务拒绝了无效请求", false),
        UPSTREAM_ERROR(CODE_UPSTREAM_ERROR, null, "模型服务暂时不可用", true),
        RESPONSE_REJECTED(CODE_RESPONSE_INVALID, null, "模型服务拒绝了当前请求", false);

        private final String code;
        private final Integer httpStatus;
        private final String safeMessage;
        private final boolean retryable;

        ErrorDefinition(
                String code, Integer httpStatus, String safeMessage, boolean retryable) {
            this.code = code;
            this.httpStatus = httpStatus;
            this.safeMessage = safeMessage;
            this.retryable = retryable;
        }

        private String code() {
            return code;
        }

        private Integer httpStatus() {
            return httpStatus;
        }

        private String safeMessage() {
            return safeMessage;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}
