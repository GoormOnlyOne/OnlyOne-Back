package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;

import java.util.List;

public interface NotificationRepositoryCustom {

    List<NotificationItemDto> findNotificationsByUserId(Long userId, Long cursor, int size);

    Long countUnreadByUserId(Long userId);

    Notification findByIdWithFetchJoin(Long notificationId);

    long markAllAsReadByUserId(Long userId);

    void markSseSentByIds(List<Long> notificationIds);
}
