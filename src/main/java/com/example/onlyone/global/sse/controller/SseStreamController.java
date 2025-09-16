package com.example.onlyone.global.sse.controller;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.sse.dto.SseConnectionStatusResponseDto;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.sse.service.SseEmittersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "SSE", description = "실시간 스트림 API")
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseStreamController {

    private final SseEmittersService sseEmittersService;
    private final UserService userService;

    @Operation(
            summary = "실시간 알림 스트림 연결",
            description = "JWT 기반 인증을 통한 Server-Sent Events 실시간 알림 수신. Last-Event-ID 헤더로 재연결 시 놓친 메시지 복구 가능",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping(value = {"/subscribe"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @Parameter(description = "마지막으로 받은 이벤트 ID (재연결 시 사용)")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        Long userId = userService.getCurrentUserId();
        return sseEmittersService.createSseConnection(userId, lastEventId);
    }

    @Operation(
            summary = "SSE 연결 상태 확인",
            description = "현재 사용자의 SSE 연결 상태를 확인합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/status")
    public ResponseEntity<CommonResponse<SseConnectionStatusResponseDto>> getConnectionStatus() {
        
        Long userId = userService.getCurrentUserId();
        boolean isConnected = sseEmittersService.isUserConnected(userId);

        SseConnectionStatusResponseDto response = SseConnectionStatusResponseDto.builder()
                .userId(userId)
                .connected(isConnected)
                .totalConnections(sseEmittersService.getActiveConnectionCount())
                .lastConnectedAt(sseEmittersService.getLastConnectedTime(userId))
                .connectionDuration(sseEmittersService.getConnectionDuration(userId))
                .build();

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}