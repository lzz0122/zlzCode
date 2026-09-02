package com.zlzcode.codeagent.agent.exception;

public final class RunException extends RuntimeException {

    public static final String NOT_FOUND_CODE = "RUN_NOT_FOUND";
    public static final String IDEMPOTENCY_CONFLICT_CODE = "RUN_IDEMPOTENCY_CONFLICT";
    public static final String STATE_CORRUPTED_CODE = "RUN_STATE_CORRUPTED";

    private final String code;
    private final boolean retryable;

    private RunException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public static RunException notFound() {
        return new RunException(NOT_FOUND_CODE, "Run 不存在", false);
    }

    public static RunException idempotencyConflict() {
        return new RunException(IDEMPOTENCY_CONFLICT_CODE, "幂等键已用于不同请求", false);
    }

    public static RunException stateCorrupted() {
        return new RunException(STATE_CORRUPTED_CODE, "Run 状态损坏", false);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
