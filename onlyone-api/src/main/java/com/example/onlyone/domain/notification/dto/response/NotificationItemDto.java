package com.example.onlyone.domain.notification.dto.response;

import com.example.onlyone.domain.notification.entity.NotificationType;

import java.time.LocalDateTime;

public record NotificationItemDto(
    Long notificationId,
    String content,
    NotificationType type,
    boolean isRead,
    LocalDateTime createdAt
) {
}
