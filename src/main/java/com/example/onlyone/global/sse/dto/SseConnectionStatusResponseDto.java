package com.example.onlyone.global.sse.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * SSE 연결 상태 응답 DTO
 */
@Getter
@Builder
public class SseConnectionStatusResponseDto {
    private final Long userId;
    private final boolean connected;
    private final int totalConnections;
    private final LocalDateTime lastConnectedAt;
    private final String connectionDuration;
}