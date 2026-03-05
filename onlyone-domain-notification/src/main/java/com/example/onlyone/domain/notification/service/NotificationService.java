package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 알림 서비스 파사드.
 * Command/Query 분리된 서비스에 위임한다.
 * 기존 호출자 호환성을 위해 유지.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationCommandService commandService;
    private final NotificationQueryService queryService;

    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        return queryService.getNotifications(dto);
    }

    public Long getUnreadCount() {
        return queryService.getUnreadCount();
    }

    public void markAsRead(Long notificationId) {
        commandService.markAsRead(notificationId);
    }

    public void deleteNotification(Long notificationId) {
        commandService.deleteNotification(notificationId);
    }

    public void markAllAsRead() {
        commandService.markAllAsRead();
    }

    public void createNotification(NotificationCreateDto dto) {
        commandService.createNotification(dto);
    }
}
