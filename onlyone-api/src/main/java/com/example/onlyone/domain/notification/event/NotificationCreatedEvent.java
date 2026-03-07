package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;

import java.time.LocalDateTime;

/**
 * 알림 생성 이벤트 클래스
 *
 * 엔티티 전체를 전달하지 않고, SSE 전송에 필요한 최소 필드만 담는 경량 이벤트이다.
 */
public record NotificationCreatedEvent(
        Long notificationId,
        Long userId,
        String content,
        NotificationType type,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationCreatedEvent from(Notification notification) {
        return new NotificationCreatedEvent(
                notification.getId(),
                notification.getUser().getUserId(),
                notification.getContent(),
                notification.getType(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
