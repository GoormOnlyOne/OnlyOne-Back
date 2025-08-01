package com.example.onlyone.domain.notification.dto.requestDto;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 알림 목록 항목 DTO
 *
 * 알림 목록 조회 시 각 알림 항목의 정보를 담는 DTO입니다.
 */
@Getter
@Builder
public class NotificationItemDto {

    private final Long notificationId;

    private final String content;

    private final Type type;

    @JsonProperty("isRead")
    private final Boolean isRead;

    private final LocalDateTime createdAt;

    public static NotificationItemDto from(Notification notification) {
        return NotificationItemDto.builder()
            .notificationId(notification.getNotificationId())
            .content(notification.getContent())
            .type(notification.getNotificationType().getType())
            .isRead(notification.getIsRead())
            .createdAt(notification.getCreatedAt())
            .build();
    }
}
