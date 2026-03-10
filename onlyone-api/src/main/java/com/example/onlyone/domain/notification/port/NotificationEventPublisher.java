package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;

/**
 * 알림 이벤트 발행 추상화 포트.
 * Spring ApplicationEvent 기반 구현체를 사용한다.
 */
public interface NotificationEventPublisher {

    void publish(NotificationCreatedEvent event);
}
