package com.zlzcode.codeagent.agent.exception;

public final class ApprovalException extends RuntimeException {

    public static final String NOT_FOUND_CODE = "APPROVAL_NOT_FOUND";
    public static final String STATE_CONFLICT_CODE = "APPROVAL_STATE_CONFLICT";
    public static final String PERSISTENCE_FAILED_CODE = "APPROVAL_PERSISTENCE_FAILED";
    public static final String EXPIRED_CODE = "APPROVAL_EXPIRED";

    private final String code;
    private final boolean retryable;

    private ApprovalException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public static ApprovalException notFound() {
        return new ApprovalException(NOT_FOUND_CODE, "审批记录不存在", false);
    }

    public static ApprovalException stateConflict() {
        return new ApprovalException(STATE_CONFLICT_CODE, "审批状态不允许当前操作", false);
    }

    public static ApprovalException persistenceFailed() {
        return new ApprovalException(PERSISTENCE_FAILED_CODE, "审批记录持久化失败", true);
    }

    public static ApprovalException expired() {
        return new ApprovalException(EXPIRED_CODE, "审批已过期", false);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
