package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.config.NotificationRabbitConfig;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 기반 알림 이벤트 발행기.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "rabbitmq")
public class RabbitNotificationPublisher implements NotificationEventPublisher {

    private final RabbitTemplate notificationRabbitTemplate;

    @Override
    public void publish(NotificationCreatedEvent event) {
        try {
            notificationRabbitTemplate.convertAndSend(
                    NotificationRabbitConfig.EXCHANGE,
                    NotificationRabbitConfig.ROUTING_KEY,
                    event);
            log.debug("RabbitMQ 알림 이벤트 발행: notificationId={}", event.notificationId());
        } catch (Exception e) {
            log.error("RabbitMQ 알림 이벤트 발행 실패: notificationId={}", event.notificationId(), e);
        }
    }
}
