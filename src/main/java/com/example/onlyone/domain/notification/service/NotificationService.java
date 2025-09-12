package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 알림 서비스 - 기본적인 알림 기능 제공
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationCreationService notificationCreationService;
    private final NotificationQueryService notificationQueryService;
    private final NotificationStatusService notificationStatusService;

    /**
     * 알림 생성 (기본 - 비동기)
     */
    public CompletableFuture<Notification> createNotification(User user, Type type, String... args) {
        return notificationCreationService.createNotification(user, type, args);
    }

    /**
     * 알림 생성 (동기)
     */
    public Notification createNotificationSync(User user, Type type, String... args) {
        return notificationCreationService.createNotificationSync(user, type, args);
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