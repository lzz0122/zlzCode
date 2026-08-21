package com.zlzcode.agent.workspace;

public class WorkspaceRegistryException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public WorkspaceRegistryException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
