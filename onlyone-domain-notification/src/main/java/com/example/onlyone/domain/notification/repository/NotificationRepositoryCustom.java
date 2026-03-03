package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;

import java.util.List;

public interface NotificationRepositoryCustom {

    List<NotificationItemDto> findNotificationsByUserId(Long userId, Long cursor, int size);

    Long countUnreadByUserId(Long userId);

    int markAsReadByIdAndUserId(Long notificationId, Long userId);

    /** @return true if the deleted notification was unread */
    boolean deleteByIdAndUserId(Long notificationId, Long userId);

    long markAllAsReadByUserId(Long userId);

    void markDeliveredByIds(List<Long> notificationIds);

    List<NotificationItemDto> findUndeliveredByUserId(Long userId, int limit);
}
