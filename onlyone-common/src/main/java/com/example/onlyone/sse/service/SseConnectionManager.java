package com.example.onlyone.sse.service;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.GlobalErrorCode;
import com.example.onlyone.sse.exception.SseErrorCode;
import com.example.onlyone.sse.SseConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class SseConnectionManager {

    @Value("${app.notification.sse-timeout-millis:60000}")
    private long sseTimeoutMillis;

    @Value("${app.notification.max-connections:7000}")
    private int maxConnections;

    private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();

    /** 분산 환경에서만 주입됨 (app.notification.multi-instance=true) */
    @Autowired(required = false)
    private DistributedConnectionRegistry distributedRegistry;

    public SseEmitter createConnection(Long userId) {
        if (userId == null) {
            throw new CustomException(GlobalErrorCode.UNAUTHORIZED);
        }

        if (activeConnections.size() >= maxConnections && !activeConnections.containsKey(userId)) {
            throw new CustomException(SseErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
        }

        SseConnection newConnection = SseConnection.builder()
                .userId(userId)
                .emitter(new SseEmitter(sseTimeoutMillis))
                .connectionTime(LocalDateTime.now())
                .build();

        registerConnectionCallbacks(newConnection);

        // 원자적으로 기존 연결을 교체하고 이전 연결을 안전하게 정리
        SseConnection oldConnection = activeConnections.put(userId, newConnection);
        if (oldConnection != null) {
            completeEmitterQuietly(oldConnection.getEmitter());
        }

        try {
            newConnection.getEmitter().send(SseEmitter.event()
                    .id("init_" + System.currentTimeMillis())
                    .name("connected")
                    .data("OK"));
        } catch (Exception e) {
            activeConnections.remove(userId, newConnection);
            throw new CustomException(SseErrorCode.SSE_CONNECTION_FAILED);
        }

        // 분산 레지스트리에 등록
        if (distributedRegistry != null) {
            distributedRegistry.register(userId);
        }

        return newConnection.getEmitter();
    }

    public void cleanupConnection(Long userId) {
        activeConnections.remove(userId);

        // 분산 레지스트리에서 해제
        if (distributedRegistry != null) {
            distributedRegistry.unregister(userId);
        }
    }

    public SseConnection getConnection(Long userId) {
        return activeConnections.get(userId);
    }

    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public boolean isUserConnected(Long userId) {
        return activeConnections.containsKey(userId);
    }

    public Set<Long> getActiveUserIds() {
        return Set.copyOf(activeConnections.keySet());
    }

    public void clearAllConnections() {
        try {
            activeConnections.keySet().forEach(userId -> {
                SseConnection connection = activeConnections.remove(userId);
                if (connection != null) {
                    completeEmitterQuietly(connection.getEmitter());
                    if (distributedRegistry != null) {
                        distributedRegistry.unregister(userId);
                    }
                }
            });
        } catch (Exception e) {
            throw new CustomException(SseErrorCode.SSE_CLEANUP_FAILED);
        }
    }

    /**
     * 주기적으로 타임아웃된 좀비 SSE 커넥션 정리
     * 클라이언트가 비정상 종료되어 콜백이 호출되지 않은 커넥션을 정리
     */
    @Scheduled(fixedRate = 120_000) // 2분 주기
    public void cleanupStaleConnections() {
        try {
            int connectionCount = activeConnections.size();
            if (connectionCount == 0) {
                return;
            }

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 60000) / 1000);
            AtomicInteger cleaned = new AtomicInteger(0);

            activeConnections.forEach((userId, connection) -> {
                if (connection.getConnectionTime().isBefore(cutoffTime)) {
                    SseConnection removed = activeConnections.remove(userId);
                    if (removed != null) {
                        completeEmitterQuietly(removed.getEmitter());
                        if (distributedRegistry != null) {
                            distributedRegistry.unregister(userId);
                        }
                        cleaned.incrementAndGet();
                    }
                }
            });

            // 분산 레지스트리 TTL 갱신
            if (distributedRegistry != null && !activeConnections.isEmpty()) {
                distributedRegistry.refreshTtl();
            }

            if (cleaned.get() > 0) {
                log.info("좀비 SSE 커넥션 정리: cleaned={}, remaining={}", cleaned.get(), activeConnections.size());
            }
        } catch (Exception e) {
            log.warn("SSE 커넥션 정리 중 오류", e);
        }
    }

    private void completeEmitterQuietly(SseEmitter emitter) {
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // 이미 완료된 연결 무시
            }
        }
    }

    private void registerConnectionCallbacks(SseConnection connection) {
        SseEmitter emitter = connection.getEmitter();
        Long userId = connection.getUserId();

        emitter.onCompletion(() -> cleanupConnection(userId));
        emitter.onTimeout(() -> cleanupConnection(userId));
        emitter.onError((ex) -> cleanupConnection(userId));
    }
}
