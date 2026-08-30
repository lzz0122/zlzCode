package com.zlzcode.codeagent.validation;

public class RequestContractException extends RuntimeException {

    private final String code;

    public RequestContractException(String message) {
        this("INVALID_REQUEST", message);
    }

    public RequestContractException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
