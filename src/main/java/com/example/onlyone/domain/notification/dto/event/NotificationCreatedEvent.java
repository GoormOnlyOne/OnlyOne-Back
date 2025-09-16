package com.example.onlyone.domain.notification.dto.event;

import com.example.onlyone.domain.notification.entity.Notification;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 알림 생성 이벤트 클래스
 */
@Getter
@RequiredArgsConstructor
public class NotificationCreatedEvent {
    private final Notification notification;
}