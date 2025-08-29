package com.example.onlyone.global.exception;

import com.example.onlyone.global.common.CommonResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
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

    /**
     * 비즈니스 로직 예외 처리
     * 비즈니스 규칙 위반 시 발생하는 예외를 처리합니다.
     * ErrorCode에 정의된 상태코드와 메시지를 사용하여 응답합니다.
     */
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleCustomException(CustomException e, HttpServletRequest request) {
        // 예외에서 ErrorCode 추출
        ErrorCode errorCode = e.getErrorCode();
        // 로그 기록
        logError(request, errorCode, e);

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(errorCode.name())
                .message(errorCode.getMessage())
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 유효성 검증 예외 처리 (DTO @Valid 검증 실패)
     * 요청 본문의 객체 검증(@Valid) 실패 시 발생하는 예외를 처리합니다.
     * 필드별 오류 메시지를 ErrorResponse의 validation 맵에 담아 반환합니다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // 필드별 유효성 검증 오류 메시지 수집
        Map<String, String> validationErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(
                error -> validationErrors.put(error.getField(), error.getDefaultMessage())
        );

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.getCode())
                .message(ErrorCode.INVALID_INPUT_VALUE.getMessage())
                .validation(validationErrors)
                .build();

        // 응답 생성 및 반환
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 요청 파라미터 바인딩 예외 처리
     * 요청 파라미터를 객체에 바인딩할 때 발생하는 예외를 처리합니다.
     * 필드별 오류 메시지를 ErrorResponse의 validation 맵에 담아 반환합니다.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleBindException(
            BindException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // 필드별 바인딩 오류 메시지 수집
        Map<String, String> validationErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(
                error -> validationErrors.put(error.getField(), error.getDefaultMessage())
        );

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.getCode())
                .message(ErrorCode.INVALID_INPUT_VALUE.getMessage())
                .validation(validationErrors)
                .build();

        // 응답 생성 및 반환
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 필수 요청 파라미터 누락 예외 처리
     * 필수 요청 파라미터가 누락되었을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.name())
                .message("필수 파라미터 '" + e.getParameterName() + "'이(가) 누락되었습니다.")
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 지원하지 않는 HTTP 메소드 호출 예외 처리
     * 요청한 HTTP 메소드가 해당 엔드포인트에서 지원되지 않을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.METHOD_NOT_ALLOWED, e);
        // ErrorCode에서 기본 메시지를 가져오고, 추가 정보 포함
        String errorMessage = ErrorCode.METHOD_NOT_ALLOWED.getMessage();
        // 필요하다면 추가 정보를 덧붙일 수 있습니다
        errorMessage += " 요청 메소드: " + e.getMethod();
        if (e.getSupportedHttpMethods() != null) {
            errorMessage += ", 지원 메소드: " + e.getSupportedHttpMethods();
        }

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.METHOD_NOT_ALLOWED.name())
                .message(errorMessage)
                .build();

        // 응답 생성 및 반환
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 요청 경로를 찾을 수 없는 예외 처리
     * 요청한 URL에 해당하는 핸들러를 찾을 수 없을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleNoHandlerFoundException(
            NoHandlerFoundException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INTERNAL_SERVER_ERROR, e);

        // 요청 URL을 포함한 메시지 생성
        String errorMessage = "요청한 리소스를 찾을 수 없습니다: " + e.getRequestURL();

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INTERNAL_SERVER_ERROR.name())
                .message(errorMessage)
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 메시지 변환 예외 처리 (JSON 파싱 오류 등)
     * 주로 요청 본문의 JSON을 Java 객체로 변환할 수 없을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.name())
                .message("요청 본문을 파싱할 수 없습니다. 올바른 JSON 형식인지 확인하세요.")
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 파라미터 타입 불일치 예외 처리
     * 요청 파라미터의 타입이 예상하는 타입과 일치하지 않을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // 파라미터 이름과 예상 타입 포함한 메시지 생성
        String errorMessage = "파라미터 '" + e.getName() + "'의 타입이 올바르지 않습니다. " +
                "예상 타입: " + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.name())
                .message(errorMessage)
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 제약 조건 위반 예외 처리
     * Bean Validation 애노테이션(예: @Min, @Max, @Email 등)으로 설정된
     * 제약 조건을 위반했을 때 발생하는 예외를 처리합니다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleConstraintViolationException(
            ConstraintViolationException e, HttpServletRequest request) {
        // 로그 기록
        logError(request, ErrorCode.INVALID_INPUT_VALUE, e);

        // 필드별 제약 조건 위반 메시지 수집
        Map<String, String> validationErrors = new HashMap<>();
        e.getConstraintViolations().forEach(
                violation -> {
                    String propertyPath = violation.getPropertyPath().toString();
                    String fieldName = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
                    validationErrors.put(fieldName, violation.getMessage());
                }
        );

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INVALID_INPUT_VALUE.getCode())
                .message(ErrorCode.INVALID_INPUT_VALUE.getMessage())
                .validation(validationErrors)
                .build();

        // 응답 생성 및 반환
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 서버 내부 오류 처리
     * 위의 모든 핸들러에서 처리되지 않은 예외를 처리하는 기본 핸들러입니다.
     * 주로 예상치 못한 서버 내부 오류가 발생했을 때 실행됩니다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<ErrorResponse>> handleException(
            Exception e, HttpServletRequest request) {
        // 로그 기록 (스택 트레이스 포함)
        logError(request, ErrorCode.INTERNAL_SERVER_ERROR, e);

        // ErrorResponse 생성 (필드별 오류 정보 포함)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(ErrorCode.INTERNAL_SERVER_ERROR.getCode())
                .message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
                .build();

        // 응답 생성 및 반환 (data 필드 없음)
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CommonResponse.error(errorResponse));
    }

    /**
     * 에러 로깅 메소드
     * ErrorCode와 함께 예외 정보를 로깅합니다.
     * URI, HTTP 메소드, 상태 코드, 오류 코드, 오류 메시지를 포함합니다.
     */
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

    /**
     * 일반 예외 로깅 메소드
     * ErrorCode 없이 일반 예외 정보만 로깅합니다.
     * URI, HTTP 메소드, 오류 메시지를 포함합니다.
     */
    private void logError(HttpServletRequest request, Exception e) {
        log.error("예외 발생 [{}] {}: {}",
                request.getRequestURI(),
                request.getMethod(),
                e.getMessage(),
                e
        );
    }
}