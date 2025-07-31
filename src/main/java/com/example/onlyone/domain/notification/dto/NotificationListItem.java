package com.example.onlyone.domain.notification.dto;

import com.example.onlyone.domain.notification.entity.Type;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NotificationListItem {

    private final long notificationId;
    private final String content;
    private final Type type;
    private final Boolean isRead;
    private LocalDateTime createdAt;
}