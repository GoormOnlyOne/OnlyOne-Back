package com.example.onlyone.global.reactive;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.sse.exception.SseErrorCode;
import com.example.onlyone.sse.service.DistributedConnectionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebFlux SSE 연결 관리자.
 * Servlet {@link com.example.onlyone.sse.service.SseConnectionManager}와 동등한 기능을 제공한다.
 *
 * <ul>
 *   <li>연결 수 제한 ({@code app.notification.max-connections})</li>
 *   <li>Flux.timeout 기반 자동 타임아웃 ({@code app.notification.sse-timeout-millis})</li>
 *   <li>2분 주기 zombie 커넥션 정리</li>
 *   <li>분산 레지스트리 연동 (선택)</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "webflux")
public class ReactiveConnectionManager {

    @Value("${app.notification.sse-timeout-millis:60000}")
    private long sseTimeoutMillis;

    @Value("${app.notification.max-connections:7000}")
    private int maxConnections;

    private record ConnectionEntry(
            Sinks.Many<ServerSentEvent<String>> sink,
            LocalDateTime connectionTime
    ) {}

    private final ConcurrentHashMap<Long, ConnectionEntry> connections = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private DistributedConnectionRegistry distributedRegistry;

    /**
     * 유저의 SSE 스트림을 생성하고 Flux를 반환한다.
     * 기존 연결이 있으면 완료 후 교체한다.
     */
    public Flux<ServerSentEvent<String>> createConnection(Long userId) {
        if (connections.size() >= maxConnections && !connections.containsKey(userId)) {
            throw new CustomException(SseErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
        }

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256);
        ConnectionEntry entry = new ConnectionEntry(sink, LocalDateTime.now());

        ConnectionEntry old = connections.put(userId, entry);
        if (old != null) {
            old.sink().tryEmitComplete();
        }

        if (distributedRegistry != null) {
            distributedRegistry.register(userId);
        }

        log.debug("WebFlux SSE 연결 생성: userId={}, active={}", userId, connections.size());

        return sink.asFlux()
                .timeout(Duration.ofMillis(sseTimeoutMillis))
                .onErrorResume(e -> Flux.empty())
                .doFinally(signal -> cleanupConnection(userId));
    }

    /**
     * 유저에게 SSE 이벤트를 전송한다.
     */
    public boolean send(Long userId, String eventName, String data) {
        ConnectionEntry entry = connections.get(userId);
        if (entry == null) {
            return false;
        }

        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .id("evt_" + System.currentTimeMillis())
                .event(eventName)
                .data(data)
                .build();

        Sinks.EmitResult result = entry.sink().tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("WebFlux SSE 전송 실패: userId={}, result={}", userId, result);
            cleanupConnection(userId);
            return false;
        }
        return true;
    }

    public boolean hasConnection(Long userId) {
        return connections.containsKey(userId);
    }

    public int getActiveConnectionCount() {
        return connections.size();
    }

    public Set<Long> getActiveUserIds() {
        return Set.copyOf(connections.keySet());
    }

    public void cleanupConnection(Long userId) {
        ConnectionEntry entry = connections.remove(userId);
        if (entry != null) {
            entry.sink().tryEmitComplete();
            if (distributedRegistry != null) {
                distributedRegistry.unregister(userId);
            }
            log.debug("WebFlux SSE 연결 해제: userId={}, remaining={}", userId, connections.size());
        }
    }

    public void clearAllConnections() {
        connections.keySet().forEach(this::cleanupConnection);
    }

    /**
     * 주기적으로 타임아웃된 좀비 커넥션 정리.
     * 클라이언트가 비정상 종료되어 doFinally 콜백이 호출되지 않은 커넥션을 정리한다.
     */
    @Scheduled(fixedRate = 120_000)
    public void cleanupStaleConnections() {
        try {
            if (connections.isEmpty()) {
                return;
            }

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 60000) / 1000);
            AtomicInteger cleaned = new AtomicInteger(0);

            connections.forEach((userId, entry) -> {
                if (entry.connectionTime().isBefore(cutoffTime)) {
                    cleanupConnection(userId);
                    cleaned.incrementAndGet();
                }
            });

            if (distributedRegistry != null && !connections.isEmpty()) {
                distributedRegistry.refreshTtl();
            }

            if (cleaned.get() > 0) {
                log.info("좀비 WebFlux SSE 커넥션 정리: cleaned={}, remaining={}", cleaned.get(), connections.size());
            }
        } catch (Exception e) {
            log.warn("WebFlux SSE 커넥션 정리 중 오류", e);
        }
    }
}
