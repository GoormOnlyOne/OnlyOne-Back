package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import com.example.onlyone.domain.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private static final int MAX_PAGE_SIZE = 30;

    private final NotificationStoragePort storagePort;
    private final AuthService authService;
    private final NotificationUnreadCounter unreadCounter;

    @Cacheable(value = "notificationList",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #dto.cursor() + '_' + #dto.size()")
    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        Long userId = authService.getCurrentUserId();
        int size = Math.min(dto.size(), MAX_PAGE_SIZE);

        List<NotificationItemDto> notifications =
                storagePort.findByUserId(userId, dto.cursor(), size + 1);

        log.debug("알림 조회: userId={}, count={}", userId, notifications.size());
        return buildPagedResponse(notifications, size);
    }

    @Cacheable(value = "unreadCount",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId")
    public Long getUnreadCount() {
        Long userId = authService.getCurrentUserId();
        return unreadCounter.getCount(userId);
    }

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
