package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.sse.SseEmittersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 하이브리드 Push-Pull 알림 서비스
 * - 온라인 사용자: SSE Push
 * - 오프라인 사용자: DB 저장 후 Pull
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridNotificationService {
    
    private final NotificationService notificationService;
    private final NotificationDeduplicationService deduplicationService;
    private final SseEmittersService sseEmittersService;
    private final NotificationRepository notificationRepository;
    
    /**
     * 하이브리드 알림 전송
     * 1. 중복 제거 검사
     * 2. 온라인 사용자는 즉시 Push
     * 3. 오프라인 사용자는 DB 저장 (Pull용)
     */
    public CompletableFuture<Boolean> sendHybridNotification(
            User user, 
            Type type, 
            String entityId,
            String... args) {
        
        Long userId = user.getUserId();
        LocalDateTime now = LocalDateTime.now();
        
        // 1. 중복 제거 검사
        if (!deduplicationService.shouldSendNotification(userId, type, entityId, now)) {
            log.debug("Notification skipped due to deduplication: userId={}, type={}", userId, type);
            return CompletableFuture.completedFuture(false);
        }
        
        // 2. 알림 생성 (비동기)
        return notificationService.createNotificationOptimized(user, type, args)
            .thenCompose(notification -> {
                // 3. Push 시도 (온라인 사용자)
                if (sseEmittersService.isUserConnected(userId)) {
                    return attemptPushNotification(notification);
                } else {
                    // 4. Pull용 저장 (오프라인 사용자)
                    return storePullNotification(notification);
                }
            })
            .exceptionally(ex -> {
                log.error("Failed to send hybrid notification: userId={}, type={}", userId, type, ex);
                return false;
            });
    }
    
    /**
     * SSE Push 시도
     */
    private CompletableFuture<Boolean> attemptPushNotification(Notification notification) {
        return sseEmittersService.sendEvent(
            notification.getUser().getUserId(),
            "notification",
            notification
        ).thenApply(success -> {
            if (success) {
                log.debug("Push notification sent: userId={}, id={}", 
                        notification.getUser().getUserId(), notification.getId());
                // Push 성공시 sseSent 플래그 업데이트
                updateSseSentFlag(notification.getId(), true);
                return true;
            } else {
                log.debug("Push failed, storing for pull: userId={}, id={}", 
                        notification.getUser().getUserId(), notification.getId());
                return false;
            }
        });
    }
    
    /**
     * Pull용 저장
     */
    private CompletableFuture<Boolean> storePullNotification(Notification notification) {
        // 이미 DB에 저장되어 있으므로 sseSent를 false로 유지
        log.debug("Notification stored for pull: userId={}, id={}", 
                notification.getUser().getUserId(), notification.getId());
        return CompletableFuture.completedFuture(true);
    }
    
    /**
     * 재연결시 누락된 알림 전송 (Last-Event-ID 기반)
     */
    public void sendMissedNotifications(Long userId, String lastEventId) {
        LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
        if (lastEventTime == null) {
            log.warn("Invalid lastEventId format, using 1 hour ago: {}", lastEventId);
            lastEventTime = LocalDateTime.now().minusHours(1);
        }
        
        final LocalDateTime finalLastEventTime = lastEventTime;
        List<Notification> missedNotifications = notificationRepository
            .findUnreadNotificationsByUserId(userId)
            .stream()
            .filter(notification -> notification.getCreatedAt().isAfter(finalLastEventTime))
            .sorted(java.util.Comparator.comparing(Notification::getCreatedAt))
            .toList();
        
        if (!missedNotifications.isEmpty()) {
            log.info("Sending {} missed notifications to user {}", 
                    missedNotifications.size(), userId);
            
            // 배치로 전송
            sendNotificationBatch(userId, missedNotifications);
        }
    }
    
    /**
     * 배치 알림 전송
     */
    @Async("sseEventExecutor")
    public void sendNotificationBatch(Long userId, List<Notification> notifications) {
        int successCount = 0;
        
        for (Notification notification : notifications) {
            try {
                boolean sent = sseEmittersService.sendEventSync(userId, "notification", notification);
                if (sent) {
                    updateSseSentFlag(notification.getId(), true);
                    successCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to send batched notification: id={}", notification.getId(), e);
            }
        }
        
        log.info("Batch notification completed: {}/{} sent to user {}", 
                successCount, notifications.size(), userId);
    }
    
    /**
     * Pull API용 미처리 알림 조회
     */
    public List<Notification> getPendingNotifications(Long userId, LocalDateTime since, int limit) {
        return notificationRepository.findUnreadNotificationsByUserId(userId)
            .stream()
            .filter(notification -> notification.getCreatedAt().isAfter(since))
            .sorted(java.util.Comparator.comparing(Notification::getCreatedAt))
            .limit(limit)
            .toList();
    }
    
    /**
     * Pull API용 알림 읽음 처리
     */
    public void markNotificationsAsReceived(Long userId, List<Long> notificationIds) {
        for (Long notificationId : notificationIds) {
            updateSseSentFlag(notificationId, true);
        }
        
        log.debug("Marked {} notifications as received for user {}", 
                notificationIds.size(), userId);
    }
    
    private void updateSseSentFlag(Long notificationId, boolean sent) {
        try {
            notificationRepository.updateSseSentStatus(notificationId, sent);
        } catch (Exception e) {
            log.warn("Failed to update SSE sent status: notificationId={}", notificationId, e);
        }
    }
    
    /**
     * Last-Event-ID를 LocalDateTime으로 파싱
     * SSE Event ID 형식: evt_timestamp, batch_timestamp, heartbeat_datetime
     */
    private LocalDateTime parseEventIdToDateTime(String eventId) {
        try {
            if (eventId == null || eventId.trim().isEmpty()) {
                return null;
            }
            
            if (eventId.startsWith("evt_") || eventId.startsWith("batch_")) {
                String[] parts = eventId.split("_");
                if (parts.length >= 2) {
                    long timestamp = Long.parseLong(parts[1]);
                    return LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC);
                }
            } else if (eventId.startsWith("heartbeat_")) {
                String dateTimePart = eventId.substring("heartbeat_".length());
                return LocalDateTime.parse(dateTimePart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            
            // 직접 ISO datetime 형식으로 시도
            return LocalDateTime.parse(eventId, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            
        } catch (DateTimeParseException | NumberFormatException e) {
            log.warn("Failed to parse eventId to DateTime: {}", eventId, e);
            return null;
        }
    }
}