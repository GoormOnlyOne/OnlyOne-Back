package com.example.onlyone.global.reactive;

import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.global.sse.SseMissedNotificationRecovery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * WebFlux SSE 알림 스트리밍 엔드포인트.
 *
 * <p>{@code GET /api/v1/reactive/subscribe} → {@link Flux}&lt;{@link ServerSentEvent}&gt; 반환.
 * Servlet MVC 위에서 Reactor 타입을 반환하면 Spring이 비동기 스트리밍으로 처리한다.</p>
 *
 * <p>Servlet SSE {@link com.example.onlyone.global.sse.SseStreamController}와 동등한 기능:</p>
 * <ul>
 *   <li>초기 connected 이벤트 전송</li>
 *   <li>놓친 알림 비동기 복구 (sseEventExecutor)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reactive")
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "webflux")
public class ReactiveNotificationController {

    private final ReactiveConnectionManager connectionManager;
    private final AuthService authService;
    private final SseMissedNotificationRecovery missedNotificationRecovery;
    private final Executor sseEventExecutor;

    public ReactiveNotificationController(
            ReactiveConnectionManager connectionManager,
            AuthService authService,
            SseMissedNotificationRecovery missedNotificationRecovery,
            @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.connectionManager = connectionManager;
        this.authService = authService;
        this.missedNotificationRecovery = missedNotificationRecovery;
        this.sseEventExecutor = sseEventExecutor;
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe() {
        Long userId = authService.getCurrentUserId();
        log.debug("WebFlux SSE 연결 요청: userId={}", userId);

        // 놓친 알림 비동기 복구
        CompletableFuture.runAsync(
                () -> missedNotificationRecovery.recover(userId), sseEventExecutor);

        // 초기 connected 이벤트 + 실시간 스트림
        Flux<ServerSentEvent<String>> initEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .id("init_" + System.currentTimeMillis())
                        .event("connected")
                        .data("OK")
                        .build()
        );

        Flux<ServerSentEvent<String>> stream = connectionManager.createConnection(userId);

        return Flux.concat(initEvent, stream);
    }
}
