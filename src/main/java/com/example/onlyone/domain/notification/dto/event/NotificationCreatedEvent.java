package com.example.onlyone.domain.notification.dto.event;

import com.example.onlyone.domain.notification.entity.AppNotification;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 알림 생성 이벤트 클래스 (트랜잭션 분리용)
 */
@Getter
@RequiredArgsConstructor
public class NotificationCreatedEvent {
    private final AppNotification notification;
}