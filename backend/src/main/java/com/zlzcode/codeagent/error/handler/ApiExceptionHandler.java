package com.zlzcode.codeagent.error.handler;

import com.zlzcode.codeagent.error.dto.ApiErrorDetail;
import com.zlzcode.codeagent.error.dto.ApiErrorResponse;
import com.zlzcode.codeagent.validation.RequestContractException;
import com.zlzcode.codeagent.workspace.exception.DirectoryPickerUnavailableException;
import com.zlzcode.codeagent.workspace.exception.WorkspaceRegistryException;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.exception.SessionException;
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
                exception.code(), exception.getMessage(), false);
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

    @ExceptionHandler(OpenAiIntegrationException.class)
    public ResponseEntity<ApiErrorResponse> handleOpenAiIntegration(OpenAiIntegrationException exception) {
        HttpStatus status = exception.httpStatus() == null
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.valueOf(exception.httpStatus());
        return response(status, exception.code(), exception.safeMessage(), exception.retryable());
    }

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<ApiErrorResponse> handleSession(SessionException exception) {
        HttpStatus status = switch (exception.code()) {
            case SessionException.NOT_FOUND_CODE -> HttpStatus.NOT_FOUND;
            case SessionException.RUN_ALREADY_EXISTS_CODE,
                    SessionException.RUN_NOT_FOUND_CODE -> HttpStatus.CONFLICT;
            case SessionException.PERSISTENCE_FAILED_CODE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
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
