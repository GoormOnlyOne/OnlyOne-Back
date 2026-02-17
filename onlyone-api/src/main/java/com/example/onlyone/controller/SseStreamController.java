package com.example.onlyone.controller;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.sse.dto.SseConnectionStatusResponseDto;
import com.example.onlyone.sse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseStreamController {

    private final SseConnectionManager connectionManager;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long userId = getCurrentUserId();
        log.info("SSE 연결 요청: userId={}", userId);
        return connectionManager.createConnection(userId);
    }

    @GetMapping("/status")
    public ResponseEntity<CommonResponse<SseConnectionStatusResponseDto>> getConnectionStatus() {
        Long userId = getCurrentUserId();
        boolean isConnected = connectionManager.isUserConnected(userId);

        SseConnectionStatusResponseDto response = new SseConnectionStatusResponseDto(
                userId,
                isConnected,
                connectionManager.getActiveConnectionCount(),
                connectionManager.getLastConnectedTime(userId),
                connectionManager.getConnectionDuration(userId)
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof UserPrincipal userPrincipal)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        return userPrincipal.getUserId();
    }
}
