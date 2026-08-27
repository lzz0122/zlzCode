package com.zlzcode.agent.error;

public record ApiErrorDetail(String code, String message, boolean retryable) {
}
