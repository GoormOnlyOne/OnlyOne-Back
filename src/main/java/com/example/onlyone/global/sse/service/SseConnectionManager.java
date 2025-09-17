package com.example.onlyone.global.sse.service;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.SseConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class SseConnectionManager {

    @Value("${app.notification.sse-timeout-millis:60000}")
    private long sseTimeoutMillis;

    @Value("${app.notification.max-connections:7000}")
    private int maxConnections;

    private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();

    public SseEmitter createConnection(Long userId) {
        if (userId == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        
        cleanupExistingConnection(userId);

        if (activeConnections.size() >= maxConnections) {
            throw new CustomException(ErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
        }

        SseConnection connection = SseConnection.builder()
                .userId(userId)
                .emitter(new SseEmitter(sseTimeoutMillis))
                .connectionTime(LocalDateTime.now())
                .build();

        registerConnectionCallbacks(connection);
        activeConnections.put(userId, connection);

        try {
            connection.getEmitter().send(SseEmitter.event()
                    .id("init_" + System.currentTimeMillis())
                    .name("connected")
                    .data("OK"));
        } catch (Exception e) {
            activeConnections.remove(userId);
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        }

        return connection.getEmitter();
    }

    public void cleanupConnection(Long userId) {
        activeConnections.remove(userId);
    }

    public SseConnection getConnection(Long userId) {
        return activeConnections.get(userId);
    }

    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    public boolean isUserConnected(Long userId) {
        return activeConnections.containsKey(userId);
    }

    public Set<Long> getActiveUserIds() {
        return Set.copyOf(activeConnections.keySet());
    }

    public LocalDateTime getLastConnectedTime(Long userId) {
        return Optional.ofNullable(activeConnections.get(userId))
                .map(SseConnection::getConnectionTime)
                .orElse(null);
    }

    public String getConnectionDuration(Long userId) {
        return Optional.ofNullable(activeConnections.get(userId))
                .map(connection -> {
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
                })
                .orElse(null);
    }

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
        } catch (Exception e) {
            throw new CustomException(ErrorCode.SSE_CLEANUP_FAILED);
        }
    }

    public void cleanupStaleConnections() {
        try {
            if (activeConnections.size() < 10) {
                return;
            }

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 60000) / 1000);

            List<Long> staleConnections = activeConnections.entrySet().parallelStream()
                    .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
                    .map(Map.Entry::getKey)
                    .toList();

            if (!staleConnections.isEmpty()) {
                staleConnections.parallelStream().forEach(this::cleanupConnection);
            }
        } catch (Exception e) {
            // 무시
        }
    }

    private void cleanupExistingConnection(Long userId) {
        if (userId == null) {
            return;
        }
        
        Optional.ofNullable(activeConnections.get(userId))
                .ifPresent(connection -> {
                    try {
                        connection.getEmitter().complete();
                    } catch (Exception e) {
                        // 무시
                    } finally {
                        activeConnections.remove(userId);
                    }
                });
    }

    private void registerConnectionCallbacks(SseConnection connection) {
        SseEmitter emitter = connection.getEmitter();
        Long userId = connection.getUserId();

        emitter.onCompletion(() -> cleanupConnection(userId));
        emitter.onTimeout(() -> cleanupConnection(userId));
        emitter.onError((ex) -> cleanupConnection(userId));
    }
}