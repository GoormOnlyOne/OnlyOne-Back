package com.example.onlyone.global.sse.service;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.dto.SseConnection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * SSE 연결 관리 전용 클래스
 */
@Slf4j
@Component
public class SseConnectionManager {

    @Value("${app.notification.sse-timeout-millis:300000}")
    private long sseTimeoutMillis;
    
    @Value("${app.notification.max-connections:5000}")
    private int maxConnections;

    private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
    private final AtomicLong totalConnectionsClosed = new AtomicLong(0);
    
    private final Counter connectionsCreatedCounter;
    private final Counter connectionsClosedCounter;
    private final Gauge activeConnectionsGauge;
    private final MeterRegistry meterRegistry;
    
    public SseConnectionManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.connectionsCreatedCounter = Counter.builder("sse.connections.created")
                .description("Total SSE connections created")
                .register(meterRegistry);
        
        this.connectionsClosedCounter = Counter.builder("sse.connections.closed")
                .description("Total SSE connections closed")
                .register(meterRegistry);
        
        this.activeConnectionsGauge = Gauge.builder("sse.connections.active", this, 
                SseConnectionManager::getActiveConnectionCount)
                .description("Active SSE connections")
                .register(meterRegistry);
    }

    /**
     * SSE 연결 생성 - userId만 사용 (JWT 인증 후 DB 조회 없음)
     */
    public SseEmitter createConnection(Long userId) {
        cleanupExistingConnection(userId);
        
        if (activeConnections.size() >= maxConnections) {
            int cleaned = forceCleanupStaleConnections();
            log.info("Force cleanup completed: {} stale connections removed", cleaned);
            
            if (activeConnections.size() >= maxConnections) {
                log.warn("Maximum SSE connections reached: {}/{}, rejecting userId: {}", 
                        activeConnections.size(), maxConnections, userId);
                throw new CustomException(ErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
            }
        }
        
        SseConnection connection = SseConnection.builder()
                .userId(userId)
                .cachedUser(null)  // JWT 인증이므로 User 객체 불필요
                .emitter(new SseEmitter(sseTimeoutMillis))
                .connectionTime(LocalDateTime.now())
                .build();
        
        activeConnections.put(userId, connection);
        totalConnectionsCreated.incrementAndGet();
        connectionsCreatedCounter.increment();
        
        registerConnectionCallbacks(connection);
        
        // 초기 heartbeat 전송 (예외 발생 시 내부에서 처리)
        sendInitialHeartbeat(connection);
        
        log.info("SSE connection established: userId={}, activeConnections={}/{}", 
                userId, activeConnections.size(), maxConnections);
        
        return connection.getEmitter();
    }
    
    /**
     * SSE 연결 생성 - User 객체 사용 (기존 호환성 유지)
     */
    public SseEmitter createConnection(User user) {
        Long userId = user.getUserId();
        
        cleanupExistingConnection(userId);
        
        if (activeConnections.size() >= maxConnections) {
            int cleaned = forceCleanupStaleConnections();
            log.info("Force cleanup completed: {} stale connections removed", cleaned);
            
            if (activeConnections.size() >= maxConnections) {
                log.warn("Maximum SSE connections reached: {}/{}, rejecting userId: {}", 
                        activeConnections.size(), maxConnections, userId);
                throw new CustomException(ErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
            }
        }
        
        SseConnection connection = SseConnection.builder()
                .userId(userId)
                .cachedUser(user)
                .emitter(new SseEmitter(sseTimeoutMillis))
                .connectionTime(LocalDateTime.now())
                .build();
        
        activeConnections.put(userId, connection);
        totalConnectionsCreated.incrementAndGet();
        connectionsCreatedCounter.increment();
        
        registerConnectionCallbacks(connection);
        
        // 초기 heartbeat 전송 (예외 발생 시 내부에서 처리)
        sendInitialHeartbeat(connection);
        
        log.info("SSE connection established: userId={}, activeConnections={}/{}", 
                userId, activeConnections.size(), maxConnections);
        
        return connection.getEmitter();
    }

    /**
     * 연결 정리
     */
    public void cleanupConnection(Long userId) {
        activeConnections.remove(userId);
        totalConnectionsClosed.incrementAndGet();
        connectionsClosedCounter.increment();
    }

    /**
     * 연결 조회
     */
    public SseConnection getConnection(Long userId) {
        return activeConnections.get(userId);
    }

    /**
     * 활성 연결 수 조회
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    /**
     * 사용자 연결 확인
     */
    public boolean isUserConnected(Long userId) {
        return activeConnections.containsKey(userId);
    }

    /**
     * 활성 사용자 ID 목록
     */
    public Set<Long> getActiveUserIds() {
        return activeConnections.keySet();
    }

    /**
     * 연결 시간 조회
     */
    public LocalDateTime getLastConnectedTime(Long userId) {
        SseConnection connection = activeConnections.get(userId);
        return connection != null ? connection.getConnectionTime() : null;
    }

    /**
     * 연결 지속 시간
     */
    public String getConnectionDuration(Long userId) {
        SseConnection connection = activeConnections.get(userId);
        if (connection == null) {
            return null;
        }
        
        long durationMs = connection.getDuration();
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%d시간 %d분", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds % 60);
        } else {
            return String.format("%d초", seconds);
        }
    }

    /**
     * 모든 연결 정리
     */
    public void clearAllConnections() {
        try {
            for (Long userId : new HashSet<>(activeConnections.keySet())) {
                SseConnection connection = activeConnections.remove(userId);
                if (connection != null && connection.getEmitter() != null) {
                    try {
                        connection.getEmitter().complete();
                    } catch (Exception e) {
                        // 완료된 연결 무시
                    }
                }
            }
            
            log.debug("Cleared all SSE connections");
            
        } catch (Exception e) {
            log.error("Error while clearing all connections", e);
            throw new CustomException(ErrorCode.SSE_CLEANUP_FAILED);
        }
    }

    /**
     * 만료된 연결 정리
     */
    public void cleanupStaleConnections() {
        try {
            int connectionCount = activeConnections.size();
            if (connectionCount < 10) {
                return;
            }
            
            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 60000) / 1000);
            
            List<Long> staleConnections = activeConnections.entrySet().parallelStream()
                    .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
                    .map(Map.Entry::getKey)
                    .toList();
            
            if (!staleConnections.isEmpty()) {
                staleConnections.parallelStream().forEach(this::cleanupConnection);
                log.info("Cleaned up {} stale SSE connections (was: {}, now: {})", 
                        staleConnections.size(), connectionCount, activeConnections.size());
            }
            
            if (connectionCount >= 1000) {
                log.debug("SSE cleanup completed: {} -> {} active connections", 
                         connectionCount, activeConnections.size());
            }
            
        } catch (Exception e) {
            log.error("Error during SSE cleanup, continuing gracefully", e);
        }
    }

    private void cleanupExistingConnection(Long userId) {
        SseConnection existingConnection = activeConnections.get(userId);
        if (existingConnection != null) {
            try {
                existingConnection.getEmitter().complete();
            } catch (Exception e) {
                log.debug("Error completing existing connection: {}", e.getMessage());
            } finally {
                activeConnections.remove(userId);
            }
        }
    }

    private int forceCleanupStaleConnections() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 30000) / 1000);
            
            Set<Long> staleConnections = activeConnections.entrySet().parallelStream()
                    .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            
            staleConnections.parallelStream().forEach(this::cleanupConnection);
            
            return staleConnections.size();
            
        } catch (Exception e) {
            log.error("Error during force cleanup", e);
            return 0;
        }
    }

    private void registerConnectionCallbacks(SseConnection connection) {
        SseEmitter emitter = connection.getEmitter();
        Long userId = connection.getUserId();
        
        emitter.onCompletion(() -> cleanupConnection(userId));
        emitter.onTimeout(() -> {
            log.debug("SSE connection timed out (정상 종료): userId={}, duration={}ms", 
                    userId, connection.getDuration());
            cleanupConnection(userId);
        });
        emitter.onError((ex) -> {
            cleanupConnection(userId);
        });
    }

    private void sendInitialHeartbeat(SseConnection connection) {
        try {
            String eventId = "heartbeat_" + System.currentTimeMillis();
            connection.getEmitter().send(SseEmitter.event()
                    .id(eventId)
                    .name("heartbeat")
                    .data("{\"status\":\"connected\",\"timestamp\":" + System.currentTimeMillis() + "}"));
        } catch (IOException e) {
            log.warn("Failed to send initial heartbeat for userId: {}, error: {}", connection.getUserId(), e.getMessage());
            activeConnections.remove(connection.getUserId());
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        } catch (IllegalStateException e) {
            log.warn("Emitter already completed for userId: {}, skipping heartbeat", connection.getUserId());
            activeConnections.remove(connection.getUserId());
            // 이미 완료된 경우 예외 발생하지 않고 조용히 처리
        }
    }
}