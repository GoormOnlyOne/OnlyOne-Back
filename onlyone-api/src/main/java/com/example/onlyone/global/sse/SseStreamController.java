package com.example.onlyone.global.sse;

import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.sse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/v1/sse")
@RequiredArgsConstructor
public class SseStreamController {

    private final SseConnectionManager connectionManager;
    private final AuthService authService;
    private final SseMissedNotificationRecovery missedNotificationRecovery;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long userId = authService.getCurrentUserId();
        log.debug("SSE 연결 요청: userId={}", userId);
        SseEmitter emitter = connectionManager.createConnection(userId);

        // 놓친 알림 복구를 비동기로 분리 — SSE 연결은 DB 커넥션 없이 즉시 반환
        CompletableFuture.runAsync(() -> missedNotificationRecovery.recover(userId));

        return emitter;
    }
}
