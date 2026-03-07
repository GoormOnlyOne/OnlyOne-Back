package com.example.onlyone.sse.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SseErrorCode implements ErrorCode {
    SSE_CONNECTION_FAILED(503, "NOTIFY_503_1", "SSE 연결에 실패했습니다."),
    SSE_SEND_FAILED(503, "NOTIFY_503_2", "SSE 메시지 전송에 실패했습니다."),
    SSE_CLEANUP_FAILED(500, "NOTIFY_500_5", "SSE 연결 정리 중 오류가 발생했습니다."),
    SSE_CONNECTION_LIMIT_EXCEEDED(429, "NOTIFY_429_1", "최대 SSE 연결 수를 초과했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
