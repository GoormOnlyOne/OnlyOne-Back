package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 조회 전용 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationQueryService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    /**
     * 알림 목록 조회 (Redis 캐시 적용)
     */
    @Cacheable(value = "notifications", key = "'user:' + #userId + ':' + (#cursor != null ? #cursor : 'null') + ':' + #size", unless = "#result.notifications.size() == 0")
    @Transactional(readOnly = true, timeout = 5)
    public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
        if (userId == null || userId <= 0) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        size = Math.min(size, 30);
        
        User user = findUser(userId);
        List<NotificationItemDto> notifications = 
                notificationRepository.findNotificationsByUserId(userId, cursor, size + 1);

        return buildNotificationListResponse(user, notifications, size);
    }

    /**
     * 읽지 않은 알림 개수 조회 (Redis 캐시 적용)
     */
    @Cacheable(value = "unreadCount", key = "'user:' + #userId")
    @Transactional(readOnly = true, timeout = 5)
    public Long getUnreadCount(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        
        Long count = notificationRepository.countUnreadByUserId(userId);
        return count != null ? count : 0L;
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private NotificationListResponseDto buildNotificationListResponse(User user, List<NotificationItemDto> notifications, int requestedSize) {
        boolean hasMore = notifications.size() > requestedSize;
        
        List<NotificationItemDto> actualNotifications = hasMore ? 
                notifications.subList(0, requestedSize) : notifications;
        
        Long nextCursor = actualNotifications.isEmpty() ? null :
                actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

        Long unreadCount = notificationRepository.countUnreadByUserId(user.getUserId());
        unreadCount = unreadCount != null ? unreadCount : 0L;

        return NotificationListResponseDto.builder()
                .notifications(actualNotifications)
                .cursor(nextCursor)
                .hasMore(hasMore)
                .unreadCount(unreadCount)
                .build();
    }
}