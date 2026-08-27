package com.zlzcode.codeagent.error.dto;

public record ApiErrorDetail(String code, String message, boolean retryable) {
}
