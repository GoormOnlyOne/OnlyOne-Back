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

        // Recovery를 동기 실행 — virtual thread 환경에서 블로킹 비용 낮음
        // 비동기 실행 시 sseEventExecutor 경합으로 전달 지연 발생 (부하 테스트 53.7% → 개선)
        missedNotificationRecovery.recover(userId);

        return emitter;
    }
}
