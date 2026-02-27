package com.example.onlyone.global.exception;

import com.example.onlyone.global.common.CommonResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import static com.example.onlyone.global.exception.GlobalErrorCode.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleCustomException(CustomException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        logError(request, errorCode, e);
        return errorResponse(errorCode.getStatus(), errorCode.name(), errorCode.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);
        return validationErrorResponse(INVALID_INPUT_VALUE, collectFieldErrors(e.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleBindException(
            BindException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);
        return validationErrorResponse(INVALID_INPUT_VALUE, collectFieldErrors(e.getBindingResult()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);
        return errorResponse(400, INVALID_INPUT_VALUE.name(),
                "필수 파라미터 '" + e.getParameterName() + "'이(가) 누락되었습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        logError(request, METHOD_NOT_ALLOWED, e);

        String message = METHOD_NOT_ALLOWED.getMessage() + " 요청 메소드: " + e.getMethod();
        if (e.getSupportedHttpMethods() != null) {
            message += ", 지원 메소드: " + e.getSupportedHttpMethods();
        }

        return errorResponse(405, METHOD_NOT_ALLOWED.name(), message);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleNoHandlerFoundException(
            NoHandlerFoundException e, HttpServletRequest request) {
        logError(request, INTERNAL_SERVER_ERROR, e);
        return errorResponse(404, INTERNAL_SERVER_ERROR.name(),
                "요청한 리소스를 찾을 수 없습니다: " + e.getRequestURL());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);
        return errorResponse(400, INVALID_INPUT_VALUE.name(),
                "요청 본문을 파싱할 수 없습니다. 올바른 JSON 형식인지 확인하세요.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);

        String message = "파라미터 '" + e.getName() + "'의 타입이 올바르지 않습니다. " +
                "예상 타입: " + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");

        return errorResponse(400, INVALID_INPUT_VALUE.name(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleConstraintViolationException(
            ConstraintViolationException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);

        Map<String, String> validationErrors = new HashMap<>();
        e.getConstraintViolations().forEach(violation -> {
            String propertyPath = violation.getPropertyPath().toString();
            String fieldName = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
            validationErrors.put(fieldName, violation.getMessage());
        });

        return validationErrorResponse(INVALID_INPUT_VALUE, validationErrors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleIllegalArgumentException(
            IllegalArgumentException e, HttpServletRequest request) {
        logError(request, INVALID_INPUT_VALUE, e);
        return errorResponse(400, INVALID_INPUT_VALUE.name(),
                "잘못된 입력값입니다: " + e.getMessage());
    }

    @ExceptionHandler({
        org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
        org.apache.catalina.connector.ClientAbortException.class,
        java.io.IOException.class
    })
    public ResponseEntity<Void> handleClientDisconnection(Exception e, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (e.getMessage() != null &&
            (e.getMessage().contains("Broken pipe") ||
             e.getMessage().contains("Connection reset") ||
             e.getMessage().contains("ClientAbortException"))) {
            log.debug("클라이언트 연결 중단 [{}] {} - {}: {}",
                     method, uri, e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.warn("클라이언트 통신 오류 [{}] {} - {}: {}",
                    method, uri, e.getClass().getSimpleName(), e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public ResponseEntity<?> handleAsyncRequestTimeoutException(
            org.springframework.web.context.request.async.AsyncRequestTimeoutException e,
            HttpServletRequest request) {
        log.debug("SSE 연결 타임아웃: uri={}, timeout 후 정상 종료", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e, HttpServletRequest request) {
        logError(request, INTERNAL_SERVER_ERROR, e);

        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();

        if ((accept != null && accept.contains("text/event-stream")) ||
            (contentType != null && contentType.contains("text/event-stream"))) {
            log.warn("SSE 요청에서 예외 발생, 연결 종료: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        return errorResponse(500, INTERNAL_SERVER_ERROR.getCode(),
                INTERNAL_SERVER_ERROR.getMessage());
    }

    // ========== PRIVATE HELPERS ==========

    private ResponseEntity<CommonResponse<ErrorResponse>> errorResponse(int status, String code, String message) {
        ErrorResponse body = ErrorResponse.builder()
                .code(code)
                .message(message)
                .build();
        return ResponseEntity.status(status).body(CommonResponse.error(body));
    }

    private ResponseEntity<CommonResponse<ErrorResponse>> validationErrorResponse(
            ErrorCode errorCode, Map<String, String> validation) {
        ErrorResponse body = ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .validation(validation)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonResponse.error(body));
    }

    private Map<String, String> collectFieldErrors(BindingResult bindingResult) {
        Map<String, String> errors = new HashMap<>();
        bindingResult.getFieldErrors().forEach(
                error -> errors.put(error.getField(), error.getDefaultMessage()));
        return errors;
    }

    private void logError(HttpServletRequest request, ErrorCode errorCode, Exception e) {
        log.error("예외 발생 [{}] {} - HTTP {} ({}): {}",
                request.getRequestURI(),
                request.getMethod(),
                errorCode.getStatus(),
                errorCode.getMessage(),
                e.getMessage(),
                e
        );
    }
}
