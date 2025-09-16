package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationActionDto;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.service.SseEmittersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTypeRepository notificationTypeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEmittersService sseEmittersService;

    @Transactional
    public Notification createNotification(NotificationCreateDto dto) {
        NotificationType notificationType = notificationTypeRepository.findByType(dto.getType())
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));

        Notification notification = Notification.create(dto.getUser(), notificationType, dto.getArgs());
        Notification saved = notificationRepository.save(notification);

        publishNotificationCreatedEvent(saved);
        log.info("알림 생성 완료: userId={}, type={}, id={}", dto.getUser().getUserId(), dto.getType(), saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        if (dto.getUserId() == null || dto.getUserId() <= 0) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        
        int size = Math.min(dto.getSize(), 30);
        
        List<NotificationItemDto> notifications = 
                notificationRepository.findNotificationsByUserId(dto.getUserId(), dto.getCursor(), size + 1);
        
        log.info("알림 조회: userId={}, 조회된 개수={}", dto.getUserId(), notifications.size());

        return buildNotificationListResponse(notifications, size, dto.getUserId());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "unreadCount", key = "#userId", unless = "#result == null")
    public Long getUnreadCountByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        
        Long count = notificationRepository.countUnreadByUserId(userId);
        return count != null ? count : 0L;
    }

    @Transactional
    public void markAsRead(NotificationActionDto dto) {
        Notification notification = findNotification(dto.getNotificationId());
        validateNotificationOwnership(notification, dto.getUserId());
        notification.markAsRead();
        log.info("알림 읽음 처리: userId={}, notificationId={}", dto.getUserId(), dto.getNotificationId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAllAsRead(Long userId) {
        try {
            long markedCount = notificationRepository.markAllAsReadByUserId(userId);
            
            if (markedCount > 0) {
                log.info("모든 알림 읽음 처리: userId={}, 처리된 개수={}", userId, markedCount);
            }
        } catch (Exception e) {
            log.error("모든 알림 읽음 처리 실패: userId={}", userId, e);
            throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
        }
    }

    @Transactional
    public void deleteNotification(NotificationActionDto dto) {
        Notification notification = findNotification(dto.getNotificationId());
        validateNotificationOwnership(notification, dto.getUserId());

        notificationRepository.delete(notification);
        log.info("알림 삭제: userId={}, notificationId={}", dto.getUserId(), dto.getNotificationId());
    }


    @Async
    private void publishNotificationCreatedEvent(Notification notification) {
        try {
            Long userId = notification.getUser().getUserId();
            
            // 조건부 처리: 사용자가 온라인인 경우만 이벤트 발행
            if (sseEmittersService.isUserConnected(userId)) {
                NotificationCreatedEvent event = new NotificationCreatedEvent(notification);
                eventPublisher.publishEvent(event);
                log.debug("온라인 사용자 알림 이벤트 발행: id={}, userId={}", notification.getId(), userId);
            } else {
                log.debug("오프라인 사용자 알림 이벤트 스킵: id={}, userId={}", notification.getId(), userId);
            }
        } catch (Exception e) {
            log.warn("알림 이벤트 발행 실패: id={}", notification.getId(), e);
        }
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
            log.error("Unauthorized notification access: userId={}, notificationOwnerUserId={}",
                    userId, notification.getUser().getUserId());
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    private NotificationListResponseDto buildNotificationListResponse(List<NotificationItemDto> notifications, int requestedSize, Long userId) {
        boolean hasMore = notifications.size() > requestedSize;
        
        List<NotificationItemDto> actualNotifications = hasMore ? 
                notifications.subList(0, requestedSize) : notifications;
        
        Long nextCursor = actualNotifications.isEmpty() ? null :
                actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

        Long unreadCount = notificationRepository.countUnreadByUserId(userId);
        unreadCount = unreadCount != null ? unreadCount : 0L;

        return NotificationListResponseDto.builder()
                .notifications(actualNotifications)
                .cursor(nextCursor)
                .hasMore(hasMore)
                .unreadCount(unreadCount)
                .build();
    }
}