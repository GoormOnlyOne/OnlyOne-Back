package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.config.NotificationKafkaConfig;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 기반 알림 이벤트 발행기.
 * userId를 파티션 키로 사용하여 동일 사용자의 알림 순서를 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "kafka")
public class KafkaNotificationPublisher implements NotificationEventPublisher {

    private final KafkaTemplate<String, String> notificationKafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(NotificationCreatedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.userId());

            notificationKafkaTemplate.send(NotificationKafkaConfig.TOPIC, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Kafka 알림 이벤트 발행 실패: notificationId={}", event.notificationId(), ex);
                        } else {
                            log.debug("Kafka 알림 이벤트 발행: notificationId={}, partition={}",
                                    event.notificationId(), result.getRecordMetadata().partition());
                        }
                    });
        } catch (Exception e) {
            log.error("Kafka 알림 이벤트 직렬화 실패: notificationId={}", event.notificationId(), e);
        }
    }
}
