package com.example.onlyone.global.sse;

import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.sse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/sse")
public class SseStreamController {

    private final SseConnectionManager connectionManager;
    private final AuthService authService;
    private final SseMissedNotificationRecovery missedNotificationRecovery;
    private final Executor sseEventExecutor;

    public SseStreamController(
            SseConnectionManager connectionManager,
            AuthService authService,
            SseMissedNotificationRecovery missedNotificationRecovery,
            @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.connectionManager = connectionManager;
        this.authService = authService;
        this.missedNotificationRecovery = missedNotificationRecovery;
        this.sseEventExecutor = sseEventExecutor;
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long userId = authService.getCurrentUserId();
        log.debug("SSE 연결 요청: userId={}", userId);
        SseEmitter emitter = connectionManager.createConnection(userId);

        CompletableFuture.runAsync(
                () -> missedNotificationRecovery.recover(userId), sseEventExecutor);

        return emitter;
    }
}
