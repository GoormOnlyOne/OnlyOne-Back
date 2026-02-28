package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.config.NotificationRabbitConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 기반 알림 이벤트 소비자.
 * 로컬 인스턴스에 해당 유저의 연결이 있으면 전송한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "rabbitmq")
public class RabbitNotificationConsumer {

    private final NotificationDeliveryPort deliveryPort;

    @RabbitListener(queues = NotificationRabbitConfig.QUEUE)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            if (deliveryPort.isUserReachable(event.userId())) {
                NotificationSseDto dto = NotificationSseDto.from(event);
                deliveryPort.deliver(event.userId(), "notification", dto);
                log.debug("RabbitMQ 알림 전송: userId={}, notificationId={}", event.userId(), event.notificationId());
            }
        } catch (Exception e) {
            log.error("RabbitMQ 알림 이벤트 처리 실패: notificationId={}", event.notificationId(), e);
        }
    }
}
