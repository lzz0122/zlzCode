package com.zlzcode.codeagent.session.exception;

public final class SessionException extends RuntimeException {

    public static final String NOT_FOUND_CODE = "SESSION_NOT_FOUND";
    public static final String STATE_CORRUPTED_CODE = "SESSION_STATE_CORRUPTED";
    public static final String PERSISTENCE_FAILED_CODE = "SESSION_PERSISTENCE_FAILED";
    public static final String RUN_ALREADY_EXISTS_CODE = "SESSION_RUN_ALREADY_EXISTS";
    public static final String RUN_NOT_FOUND_CODE = "SESSION_RUN_NOT_FOUND";

    private final String code;
    private final boolean retryable;

    private SessionException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public static SessionException notFound() {
        return new SessionException(NOT_FOUND_CODE, "会话不存在或已失效", false);
    }

    public static SessionException stateCorrupted() {
        return new SessionException(STATE_CORRUPTED_CODE, "会话文件已损坏，无法继续运行", false);
    }

    public static SessionException persistenceFailed() {
        return new SessionException(PERSISTENCE_FAILED_CODE, "无法可靠保存会话，请重试", true);
    }

    public static SessionException runAlreadyExists() {
        return new SessionException(RUN_ALREADY_EXISTS_CODE, "当前运行标识已在会话中使用", false);
    }

    public static SessionException runNotFound() {
        return new SessionException(RUN_NOT_FOUND_CODE, "当前运行不属于该会话", false);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
