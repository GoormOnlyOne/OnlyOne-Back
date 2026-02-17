package com.example.onlyone.domain.notification.dto.event;

import com.example.onlyone.domain.notification.entity.Notification;

/**
 * 알림 생성 이벤트 클래스
 */
public record NotificationCreatedEvent(Notification notification) {
}
