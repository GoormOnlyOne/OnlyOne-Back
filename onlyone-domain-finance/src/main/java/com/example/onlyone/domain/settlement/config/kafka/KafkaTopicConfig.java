package com.example.onlyone.domain.settlement.config.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaTopicConfig {

    private final KafkaProperties props;

    @Value("${app.kafka.topic.replicas:1}")
    private int replicas;

    @Value("${app.kafka.topic.min-insync-replicas:1}")
    private int minInsyncReplicas;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> cfg = new HashMap<>();
        var servers = props.getProducer().getCommonConfig().getBootstrapServers();
        cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", servers));
        return new KafkaAdmin(cfg);
    }

    // settlement.process.v1
    @Bean
    public org.apache.kafka.clients.admin.NewTopic settlementProcessTopic() {
        String topic = props.getProducer().getSettlementProcessProducerConfig().getTopic();
        return TopicBuilder.name(topic)
                .partitions(12)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInsyncReplicas))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }

    // user-settlement.result.v1
    @Bean
    public org.apache.kafka.clients.admin.NewTopic userSettlementResultTopic() {
        String topic = props.getConsumer().getUserSettlementLedgerConsumerConfig().getTopic();
        return TopicBuilder.name(topic)
                .partitions(24)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInsyncReplicas))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(14).toMillis()))
                .build();
    }
}