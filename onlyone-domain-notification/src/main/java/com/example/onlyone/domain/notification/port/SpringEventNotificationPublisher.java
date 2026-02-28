package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Spring ApplicationEvent 기반 알림 이벤트 발행기.
 * 단일 인스턴스 환경에서 사용한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "spring", matchIfMissing = true)
public class SpringEventNotificationPublisher implements NotificationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(NotificationCreatedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
