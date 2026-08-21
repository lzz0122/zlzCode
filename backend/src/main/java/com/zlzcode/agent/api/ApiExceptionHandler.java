package com.zlzcode.agent.api;

import com.zlzcode.agent.contract.ApiErrorDetail;
import com.zlzcode.agent.contract.ApiErrorResponse;
import com.zlzcode.agent.contract.RequestContractException;
import com.zlzcode.agent.workspace.DirectoryPickerUnavailableException;
import com.zlzcode.agent.workspace.WorkspaceRegistryException;
import com.zlzcode.agent.llm.ModelDiscoveryService.ModelDiscoveryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(WebExchangeBindException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorResponse> handleInput(ServerWebInputException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(RequestContractException.class)
    public ResponseEntity<ApiErrorResponse> handleContract(RequestContractException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(DirectoryPickerUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handlePicker(DirectoryPickerUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "DIRECTORY_PICKER_UNAVAILABLE", exception.getMessage(), true);
    }

    @ExceptionHandler(WorkspaceRegistryException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkspace(WorkspaceRegistryException exception) {
        HttpStatus status = exception.code().contains("PERSISTENCE")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(ModelDiscoveryException.class)
    public ResponseEntity<ApiErrorResponse> handleModelDiscovery(ModelDiscoveryException exception) {
        return response(HttpStatus.valueOf(exception.status()),
                "MODEL_DISCOVERY_FAILED", exception.safeMessage(), exception.retryable());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            boolean retryable) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiErrorDetail(code, message, retryable)));
    }
}
