package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
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

    // ========== 조회 ==========

    /** 커서 기반 페이징으로 알림 목록 조회 */
    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        Long userId = getCurrentUserId();
        int size = Math.min(dto.size(), MAX_PAGE_SIZE);

        // size + 1개를 조회하여 다음 페이지 존재 여부 판별
        List<NotificationItemDto> notifications =
                notificationRepository.findNotificationsByUserId(userId, dto.cursor(), size + 1);

        log.debug("알림 조회: userId={}, count={}", userId, notifications.size());
        return buildPagedResponse(notifications, size);
    }

    /** 읽지 않은 알림 개수 조회 */
    public Long getUnreadCount() {
        Long userId = getCurrentUserId();
        return notificationRepository.countUnreadByUserId(userId);
    }

    // ========== 상태 변경 ==========

    /** 단건 읽음 처리 */
    @Transactional
    public void markAsRead(Long notificationId) {
        Long userId = getCurrentUserId();
        Notification notification = findNotificationOrThrow(notificationId);
        validateOwnership(notification, userId);
        notification.markAsRead();
        log.debug("알림 읽음: userId={}, notificationId={}", userId, notificationId);
    }

    /** 전체 읽음 처리 (벌크 업데이트) */
    @Transactional
    public void markAllAsRead() {
        Long userId = getCurrentUserId();
        long markedCount = notificationRepository.markAllAsReadByUserId(userId);
        if (markedCount > 0) {
            log.info("모든 알림 읽음: userId={}, count={}", userId, markedCount);
        }
    }

    /** 단건 삭제 */
    @Transactional
    public void deleteNotification(Long notificationId) {
        Long userId = getCurrentUserId();
        Notification notification = findNotificationOrThrow(notificationId);
        validateOwnership(notification, userId);
        notificationRepository.delete(notification);
        log.debug("알림 삭제: userId={}, notificationId={}", userId, notificationId);
    }

    // ========== 다른 도메인 서비스용 ==========

    /** 알림 생성 후 SSE 배치 전송을 위한 이벤트 발행 */
    @Transactional
    public void createNotification(NotificationCreateDto dto) {
        Notification notification = Notification.create(dto.user(), dto.type(), dto.args());
        notificationRepository.save(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(notification));
        log.debug("알림 생성: userId={}, type={}, id={}",
                dto.user().getUserId(), dto.type(), notification.getId());
    }

    // ========== private ==========

    private Long getCurrentUserId() {
        return authService.getCurrentUserId();
    }

    private Notification findNotificationOrThrow(Long notificationId) {
        Notification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
        if (notification == null) {
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return notification;
    }

    private void validateOwnership(Notification notification, Long userId) {
        if (!notification.getUser().getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /** size + 1 패턴으로 커서 기반 페이징 응답 생성 */
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
