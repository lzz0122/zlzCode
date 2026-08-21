package com.zlzcode.agent.contract;

public record ApiErrorDetail(String code, String message, boolean retryable) {
}
