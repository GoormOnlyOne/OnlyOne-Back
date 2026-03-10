package com.example.onlyone.global.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 단일 Aspect로 애플리케이션 전체 커스텀 메트릭 수집.
 * 기존 코드를 수정하지 않고 AOP로 횡단 관심사를 처리한다.
 *
 * 수집 대상:
 * - SSE: 연결 생성/해제, 이벤트 전송 성공/실패
 * - JWT: REST 인증 성공/실패
 * - STOMP: WebSocket 인증 성공/실패
 * - WebSocket: 채팅 메시지 처리
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MonitoringAspect {

    private final MeterRegistry registry;

    // ── SSE Counters ──
    private Counter sseConnectionCreated;
    private Counter sseConnectionClosed;
    private Counter sseConnectionFailed;
    private Counter sseEventSuccess;
    private Counter sseEventFailure;
    private Counter sseStaleCleanup;

    // ── JWT / Security Counters ──
    private Counter jwtAuthSuccess;
    private Counter jwtAuthFailure;
    private Counter jwtInactiveRejected;

    // ── STOMP / WebSocket Counters ──
    private Counter stompAuthSuccess;
    private Counter stompAuthFailure;
    private Counter wsMessageReceived;
    private Counter wsMessagePublished;
    private Counter wsMessageError;

    // ── Timers ──
    private Timer sseConnectionTimer;
    private Timer wsMessageTimer;

    @PostConstruct
    void init() {
        // SSE
        sseConnectionCreated = Counter.builder("sse.connections.created")
                .description("Total SSE connections created").register(registry);
        sseConnectionClosed = Counter.builder("sse.connections.closed")
                .description("Total SSE connections closed").register(registry);
        sseConnectionFailed = Counter.builder("sse.connections.failed")
                .description("Total SSE connection creation failures").register(registry);
        sseEventSuccess = Counter.builder("sse.events.sent")
                .tag("result", "success").description("SSE events sent successfully").register(registry);
        sseEventFailure = Counter.builder("sse.events.sent")
                .tag("result", "failure").description("SSE events failed to send").register(registry);
        sseStaleCleanup = Counter.builder("sse.connections.stale.cleaned")
                .description("Stale SSE connections cleaned up").register(registry);
        sseConnectionTimer = Timer.builder("sse.connection.duration")
                .description("SSE connection creation time").register(registry);

        // JWT
        jwtAuthSuccess = Counter.builder("security.jwt.auth")
                .tag("result", "success").description("JWT auth successes").register(registry);
        jwtAuthFailure = Counter.builder("security.jwt.auth")
                .tag("result", "failure").description("JWT auth failures").register(registry);
        jwtInactiveRejected = Counter.builder("security.jwt.inactive.rejected")
                .description("Inactive users rejected by JWT filter").register(registry);

        // STOMP
        stompAuthSuccess = Counter.builder("security.stomp.auth")
                .tag("result", "success").description("STOMP auth successes").register(registry);
        stompAuthFailure = Counter.builder("security.stomp.auth")
                .tag("result", "failure").description("STOMP auth failures").register(registry);

        // WebSocket Chat
        wsMessageReceived = Counter.builder("websocket.messages")
                .tag("type", "received").description("WebSocket messages received").register(registry);
        wsMessagePublished = Counter.builder("websocket.messages")
                .tag("type", "published").description("WebSocket messages published").register(registry);
        wsMessageError = Counter.builder("websocket.messages")
                .tag("type", "error").description("WebSocket message errors").register(registry);
        wsMessageTimer = Timer.builder("websocket.message.duration")
                .description("WebSocket message processing time").register(registry);
    }

    // ═══════════════════════════════════════════════════════════
    // SSE
    // ═══════════════════════════════════════════════════════════

    @Around("execution(* com.example.onlyone.sse.service.SseConnectionManager.createConnection(..))")
    public Object aroundCreateConnection(ProceedingJoinPoint pjp) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        try {
            Object result = pjp.proceed();
            sseConnectionCreated.increment();
            return result;
        } catch (Throwable e) {
            sseConnectionFailed.increment();
            throw e;
        } finally {
            sample.stop(sseConnectionTimer);
        }
    }

    @AfterReturning("execution(* com.example.onlyone.sse.service.SseConnectionManager.cleanupConnection(..))")
    public void afterCleanupConnection() {
        sseConnectionClosed.increment();
    }

    @AfterReturning("execution(* com.example.onlyone.sse.service.SseConnectionManager.cleanupStaleConnections(..))")
    public void afterStaleCleanup() {
        sseStaleCleanup.increment();
    }

    @SuppressWarnings("unchecked")
    @Around("execution(* com.example.onlyone.sse.service.SseEventSender.sendEvent(..))")
    public Object aroundSendEvent(ProceedingJoinPoint pjp) throws Throwable {
        CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) pjp.proceed();
        return future.whenComplete((success, ex) -> {
            if (ex != null || !Boolean.TRUE.equals(success)) {
                sseEventFailure.increment();
            } else {
                sseEventSuccess.increment();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // JWT Authentication (REST)
    // ═══════════════════════════════════════════════════════════

    @Around("execution(* com.example.onlyone.global.filter.JwtTokenParser.parseToken(..))")
    public Object aroundJwtParse(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object result = pjp.proceed();
            jwtAuthSuccess.increment();
            return result;
        } catch (Throwable e) {
            jwtAuthFailure.increment();
            if (e.getMessage() != null && e.getMessage().contains("WITHDRAWN")) {
                jwtInactiveRejected.increment();
            }
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // STOMP Authentication (WebSocket)
    // ═══════════════════════════════════════════════════════════

    @Around("execution(* com.example.onlyone.global.filter.StompAuthChannelInterceptor.preSend(..))")
    public Object aroundStompAuth(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object result = pjp.proceed();
            stompAuthSuccess.increment();
            return result;
        } catch (Throwable e) {
            stompAuthFailure.increment();
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket Chat Messages
    // ═══════════════════════════════════════════════════════════

    @Around("execution(* com.example.onlyone.domain.chat.controller.ChatWebSocketController.sendMessage(..))")
    public Object aroundWsSendMessage(ProceedingJoinPoint pjp) throws Throwable {
        wsMessageReceived.increment();
        Timer.Sample sample = Timer.start(registry);
        try {
            Object result = pjp.proceed();
            wsMessagePublished.increment();
            return result;
        } catch (Throwable e) {
            wsMessageError.increment();
            throw e;
        } finally {
            sample.stop(wsMessageTimer);
        }
    }
}
