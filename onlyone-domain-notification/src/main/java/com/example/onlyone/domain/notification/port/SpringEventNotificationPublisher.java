package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Spring ApplicationEvent 기반 알림 이벤트 발행기.
 */
@Component
@RequiredArgsConstructor
public class SpringEventNotificationPublisher implements NotificationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(NotificationCreatedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
