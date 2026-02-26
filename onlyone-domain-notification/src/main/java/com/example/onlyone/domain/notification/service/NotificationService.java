package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 30;

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthService authService;
    private final NotificationUnreadCounter unreadCounter;

    // ========== 조회 ==========

    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        Long userId = authService.getCurrentUserId();
        int size = Math.min(dto.size(), MAX_PAGE_SIZE);

        List<NotificationItemDto> notifications =
                notificationRepository.findNotificationsByUserId(userId, dto.cursor(), size + 1);

        log.debug("알림 조회: userId={}, count={}", userId, notifications.size());
        return buildPagedResponse(notifications, size);
    }

    public Long getUnreadCount() {
        Long userId = authService.getCurrentUserId();
        return unreadCounter.getCount(userId);
    }

    // ========== 상태 변경 ==========

    @Transactional
    public void markAsRead(Long notificationId) {
        Long userId = authService.getCurrentUserId();
        int updated = notificationRepository.markAsReadByIdAndUserId(notificationId, userId);
        if (updated > 0) {
            unreadCounter.decrement(userId);
        }
        log.debug("알림 읽음: userId={}, notificationId={}, updated={}", userId, notificationId, updated);
    }

    @Transactional
    public void markAllAsRead() {
        Long userId = authService.getCurrentUserId();
        long markedCount = notificationRepository.markAllAsReadByUserId(userId);
        if (markedCount > 0) {
            unreadCounter.reset(userId);
            log.debug("모든 알림 읽음: userId={}, count={}", userId, markedCount);
        }
    }

    @Transactional
    public void deleteNotification(Long notificationId) {
        Long userId = authService.getCurrentUserId();
        boolean wasUnread = notificationRepository.deleteByIdAndUserId(notificationId, userId);
        if (wasUnread) {
            unreadCounter.decrement(userId);
        }
        log.debug("알림 삭제: userId={}, notificationId={}", userId, notificationId);
    }

    // ========== 다른 도메인 서비스용 ==========

    @Transactional
    public void createNotification(NotificationCreateDto dto) {
        Notification notification = Notification.create(dto.user(), dto.type(), dto.args());
        notificationRepository.save(notification);

        unreadCounter.increment(dto.user().getUserId());

        eventPublisher.publishEvent(new NotificationCreatedEvent(notification));
        log.debug("알림 생성: userId={}, type={}, id={}",
                dto.user().getUserId(), dto.type(), notification.getId());
    }

    // ========== private ==========

    private NotificationListResponseDto buildPagedResponse(
            List<NotificationItemDto> notifications, int requestedSize) {

        boolean hasMore = notifications.size() > requestedSize;
        List<NotificationItemDto> page = hasMore
                ? notifications.subList(0, requestedSize)
                : notifications;

        Long nextCursor = page.isEmpty()
                ? null
                : page.get(page.size() - 1).notificationId();

        return new NotificationListResponseDto(page, nextCursor, hasMore);
    }
}
