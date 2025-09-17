package com.example.onlyone.global.sse.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.sse.SseConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmittersService implements InitializingBean, DisposableBean {

    @Value("${app.notification.cleanup-interval-minutes:2}")
    private int cleanupIntervalMinutes;

    private final NotificationRepository notificationRepository;
    private final SseConnectionManager connectionManager;
    private final SseEventSender eventSender;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    
    @Qualifier("notificationExecutor")
    private final Executor notificationExecutor;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SseEmitter createSseConnection(Long userId, String lastEventId) {
        SseEmitter emitter = connectionManager.createConnection(userId);
        
        Optional.ofNullable(connectionManager.getConnection(userId))
                .ifPresent(connection -> 
                    CompletableFuture.runAsync(() -> {
                        try {
                            Optional.ofNullable(lastEventId)
                                    .filter(id -> !id.isEmpty())
                                    .ifPresentOrElse(
                                            id -> sendMissedNotifications(connection, id),
                                            () -> sendAllUnsentNotifications(connection, userId)
                                    );
                        } catch (Exception e) {
                            log.error("미전송 알림 전솠 실패: userId={}", userId, e);
                        }
                    }, notificationExecutor)
                );
        
        return emitter;
    }

    private void sendAllUnsentNotifications(SseConnection connection, Long userId) {
        try {
            Optional.of(notificationRepository.findUnsentNotificationsByUserId(userId))
                    .filter(notifications -> !notifications.isEmpty())
                    .ifPresent(notifications -> sendNotifications(connection, notifications));
        } catch (Exception e) {
            log.error("미전송 알림 전송 실패: userId={}", userId, e);
        }
    }

    public CompletableFuture<Boolean> sendEvent(Long userId, String eventName, Object data) {
        return Optional.of(userId)
                .filter(connectionManager::isUserConnected)
                .map(id -> eventSender.sendEvent(id, eventName, data))
                .orElse(CompletableFuture.completedFuture(false));
    }

    public int getActiveConnectionCount() {
        return connectionManager.getActiveConnectionCount();
    }

    public boolean isUserConnected(Long userId) {
        return connectionManager.isUserConnected(userId);
    }

    public Set<Long> getActiveUserIds() {
        return connectionManager.getActiveUserIds();
    }

    public LocalDateTime getLastConnectedTime(Long userId) {
        return connectionManager.getLastConnectedTime(userId);
    }

    public String getConnectionDuration(Long userId) {
        return connectionManager.getConnectionDuration(userId);
    }

    public void clearAllConnections() {
        connectionManager.clearAllConnections();
    }

    @Override
    public void afterPropertiesSet() {
        cleanupScheduler.scheduleWithFixedDelay(
                connectionManager::cleanupStaleConnections,
                cleanupIntervalMinutes,
                cleanupIntervalMinutes,
                TimeUnit.MINUTES
        );

        log.info("SSE 서비스 시작 - cleanup: {}min", cleanupIntervalMinutes);
    }

    @Override
    public void destroy() {
        log.info("SSE 서비스 종료");

        connectionManager.clearAllConnections();

        cleanupScheduler.shutdown();

        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("SSE 서비스 종료 완료");
    }

    private void sendMissedNotifications(SseConnection connection, String lastEventId) {
        try {
            Optional.ofNullable(parseEventIdToDateTime(lastEventId))
                    .map(lastEventTime -> notificationRepository
                            .findUnsentNotificationsByUserIdAfterTime(connection.getUserId(), lastEventTime))
                    .filter(notifications -> !notifications.isEmpty())
                    .ifPresent(notifications -> sendNotifications(connection, notifications));
        } catch (Exception e) {
            log.error("누락된 알림 처리 실패: userId={}", connection.getUserId(), e);
        }
    }

    private void sendNotifications(SseConnection connection, List<Notification> notifications) {
        for (int i = 0; i < notifications.size(); i++) {
            Notification notification = notifications.get(i);
            try {
                String eventId = "evt_" + System.currentTimeMillis() + "_" + i;
                String notificationJson = objectMapper.writeValueAsString(notification);

                connection.getEmitter().send(SseEmitter.event()
                        .id(eventId)
                        .name("notification")
                        .data(notificationJson));

                CompletableFuture.runAsync(() -> {
                    try {
                        notificationRepository.updateSseSentStatus(notification.getId(), true);
                    } catch (Exception e) {
                        log.warn("SSE 상태 업데이트 실패: notificationId={}", notification.getId());
                    }
                }, notificationExecutor);

            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Broken pipe")) {
                    break;
                }
                log.error("알림 전송 실패: userId={}, notificationId={}",
                        connection.getUserId(), notification.getId(), e);
                break;
            }
        }
    }

    private LocalDateTime parseEventIdToDateTime(String eventId) {
        try {
            if (eventId.startsWith("evt_")) {
                String[] parts = eventId.split("_");
                if (parts.length >= 2) {
                    long timestamp = Long.parseLong(parts[1]);
                    return LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC);
                }
            } else if (eventId.startsWith("heartbeat_")) {
                String dateTimePart = eventId.substring("heartbeat_".length());
                return LocalDateTime.parse(dateTimePart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                try {
                    Long.parseLong(eventId);
                    return LocalDateTime.now().minusHours(1);
                } catch (NumberFormatException ignored) {
                    // 알 수 없는 형식
                }
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            log.debug("Failed to parse eventId: {}", eventId);
        }
        return null;
    }
}