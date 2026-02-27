package com.example.onlyone.domain.notification.dto.response;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;

import java.time.LocalDateTime;

public record NotificationSseDto(
    Long notificationId,
    String content,
    NotificationType type,
    boolean isRead,
    LocalDateTime createdAt
) {
    public static NotificationSseDto from(Notification notification) {
        return new NotificationSseDto(
            notification.getId(),
            notification.getContent(),
            notification.getType(),
            notification.isRead(),
            notification.getCreatedAt()
        );
    }

    public static NotificationSseDto from(NotificationCreatedEvent event) {
        return new NotificationSseDto(
            event.notificationId(),
            event.content(),
            event.type(),
            event.isRead(),
            event.createdAt()
        );
    }
}
