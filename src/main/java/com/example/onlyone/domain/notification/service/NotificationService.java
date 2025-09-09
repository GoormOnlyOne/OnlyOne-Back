package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.example.onlyone.domain.notification.entity.NotificationPriority;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 알림 파사드 서비스 - 통합된 인터페이스 제공
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationCreationService notificationCreationService;
    private final NotificationQueryService notificationQueryService;
    private final NotificationStatusService notificationStatusService;
    private final NotificationBatchService notificationBatchService;

    /**
     * 알림 생성 - 기본적으로 배치 처리 사용 (성능 최적화)
     */
    public CompletableFuture<Integer> createNotification(User user, Type type, String... args) {
        return notificationBatchService.sendBatchNotifications(
            List.of(user), type, NotificationPriority.NORMAL, args
        );
    }

    /**
     * 우선순위 알림 생성
     */
    public CompletableFuture<Integer> createNotificationWithPriority(User user, Type type, NotificationPriority priority, String... args) {
        return notificationBatchService.sendBatchNotifications(
            List.of(user), type, priority, args
        );
    }
    
    /**
     * 대량 알림 생성
     */
    public CompletableFuture<Integer> createBulkNotifications(List<User> users, Type type, String... args) {
        return notificationBatchService.sendBatchNotifications(
            users, type, NotificationPriority.NORMAL, args
        );
    }
    
    /**
     * 내부용 - 하이브리드 서비스에서 사용
     */
    public CompletableFuture<Notification> createNotificationOptimized(User user, Type type, String... args) {
        return notificationCreationService.createNotificationOptimized(user, type, args);
    }

    /**
     * 알림 목록 조회
     */
    public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
        return notificationQueryService.getNotifications(userId, cursor, size);
    }

    /**
     * 읽지 않은 알림 개수 조회
     */
    public Long getUnreadCount(Long userId) {
        return notificationQueryService.getUnreadCount(userId);
    }

    /**
     * 알림 읽음 처리
     */
    public void markAsRead(Long notificationId, Long userId) {
        notificationStatusService.markAsRead(notificationId, userId);
    }

    /**
     * 모든 알림 읽음 처리
     */
    public void markAllAsRead(Long userId) {
        notificationStatusService.markAllAsRead(userId);
    }

    /**
     * 알림 삭제
     */
    public void deleteNotification(Long userId, Long notificationId) {
        notificationStatusService.deleteNotification(userId, notificationId);
    }
}