package com.example.onlyone.sse.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * SSE 연결 상태 응답 DTO
 */
@Schema(description = "SSE 연결 상태 정보")
public record SseConnectionStatusResponseDto(
    @Schema(description = "사용자 ID", example = "1234567890") Long userId,
    @Schema(description = "연결 여부", example = "true") boolean connected,
    @Schema(description = "전체 활성 연결 수", example = "42") int totalConnections,
    @Schema(description = "마지막 연결 시간", example = "2024-01-01T12:00:00") LocalDateTime lastConnectedAt,
    @Schema(description = "연결 지속 시간", example = "5분 30초") String connectionDuration
) {
}
