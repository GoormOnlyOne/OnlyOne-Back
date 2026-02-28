package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.config.NotificationKafkaConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 기반 알림 이벤트 소비자.
 * 로컬 인스턴스에 해당 유저의 연결이 있으면 전송한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "kafka")
public class KafkaNotificationConsumer {

    private final NotificationDeliveryPort deliveryPort;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = NotificationKafkaConfig.TOPIC,
            groupId = NotificationKafkaConfig.GROUP_ID,
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onNotificationCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            NotificationCreatedEvent event = objectMapper.readValue(
                    record.value(), NotificationCreatedEvent.class);

            if (deliveryPort.isUserReachable(event.userId())) {
                NotificationSseDto dto = NotificationSseDto.from(event);
                deliveryPort.deliver(event.userId(), "notification", dto);
                log.debug("Kafka 알림 전송: userId={}, notificationId={}", event.userId(), event.notificationId());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Kafka 알림 이벤트 처리 실패: offset={}", record.offset(), e);
            ack.acknowledge();
        }
    }
}
