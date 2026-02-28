package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;

/**
 * 알림 이벤트 발행 추상화 포트.
 * Spring Events, Kafka, RabbitMQ, Redis Pub/Sub 등 구현체를
 * {@code app.notification.event-bus} 프로퍼티로 교체할 수 있다.
 */
public interface NotificationEventPublisher {

    void publish(NotificationCreatedEvent event);
}
