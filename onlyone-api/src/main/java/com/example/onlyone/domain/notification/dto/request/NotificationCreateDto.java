package com.example.onlyone.domain.notification.dto.request;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.user.entity.User;

public record NotificationCreateDto(
        User user,
        NotificationType type,
        String name
) {
}
