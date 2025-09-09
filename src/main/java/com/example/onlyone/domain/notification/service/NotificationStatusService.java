package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.metrics.SseMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 상태 관리 전용 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationStatusService {

    private final NotificationRepository notificationRepository;
    private final SseMetrics sseMetrics;

    /**
     * 알림 읽음 처리
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = findNotification(notificationId);
        validateNotificationOwnership(notification, userId);
        notification.markAsRead();
        sseMetrics.recordNotificationMarkedRead();
    }

    /**
     * 모든 알림 읽음 처리
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    @Async("dbTaskExecutor")
    public void markAllAsRead(Long userId) {
        try {
            long unreadCount = notificationRepository.countUnreadByUserId(userId);
            
            if (unreadCount > 1000) {
                log.info("Large batch markAllAsRead: userId={}, count={}", userId, unreadCount);
            }
            
            long markedCount = notificationRepository.markAllAsReadByUserId(userId);
            
            if (markedCount > 0) {
                log.debug("Marked {} notifications as read for user: {}", markedCount, userId);
                for (int i = 0; i < markedCount; i++) {
                    sseMetrics.recordNotificationMarkedRead();
                }
            }
        } catch (Exception e) {
            log.error("Failed to mark all notifications as read: userId={}", userId, e);
            throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
        }
    }

    /**
     * 알림 삭제
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteNotification(Long userId, Long notificationId) {
        Notification notification = findNotification(notificationId);
        validateNotificationOwnership(notification, userId);

        notificationRepository.delete(notification);
        sseMetrics.recordNotificationDeleted();

        log.info("Notification deleted: id={}", notificationId);
    }

    private Notification findNotification(Long notificationId) {
        Notification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
        if (notification == null) {
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return notification;
    }

    private void validateNotificationOwnership(Notification notification, Long userId) {
        if (!notification.getUser().getUserId().equals(userId)) {
            log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
                    userId, notification.getUser().getUserId());
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }
}