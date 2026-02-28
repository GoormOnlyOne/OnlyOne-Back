package com.example.onlyone.domain.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

/**
 * 알림 이벤트 버스 Kafka 설정.
 * {@code app.notification.event-bus=kafka} 일 때 활성화된다.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "kafka")
public class NotificationKafkaConfig {

    public static final String TOPIC = "notification.created.v1";
    public static final String GROUP_ID = "notification-delivery";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public NewTopic notificationCreatedTopic() {
        return TopicBuilder.name(TOPIC)
                .partitions(6)
                .replicas(1)
                .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean("notificationKafkaProducerFactory")
    public ProducerFactory<String, String> notificationKafkaProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        ));
    }

    @Bean("notificationKafkaTemplate")
    public KafkaTemplate<String, String> notificationKafkaTemplate() {
        return new KafkaTemplate<>(notificationKafkaProducerFactory());
    }

    @Bean("notificationKafkaConsumerFactory")
    public ConsumerFactory<String, String> notificationKafkaConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }

    @Bean("notificationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationKafkaConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
