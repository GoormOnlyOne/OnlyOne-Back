package com.example.onlyone.global.config.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE;

@RequiredArgsConstructor
@Configuration
@EnableKafka // @KafkaListener를 사용하기 위한 조건
public class KafkaConsumerConfig {

    private final KafkaProperties props;

    @Bean
    public ConsumerFactory<String, String> userSettlementLedgerConsumerFactory() {
        return setConsumerFactory(props.getConsumer().getCommonConfig(), props.getSecurity());
    }

    private ConsumerFactory<String, String> setConsumerFactory(final KafkaProperties.ConsumerCommonConfig c,
                                                               final KafkaProperties.Security s) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, c.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, c.getGroupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, c.getClientId());
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, c.getTimeoutMs());
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, c.getFetchMinBytes());
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, c.getFetchMaxWaitMs());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // 보안 설정
        if (s != null && s.isEnabled()) {
            config.put("security.protocol", s.getProtocol());
            config.put("sasl.mechanism", s.getMechanism());
            config.put("sasl.jaas.config", s.getJaas());
            if (s.getSslTruststoreLocation() != null && !s.getSslTruststoreLocation().isBlank()) {
                config.put("ssl.truststore.location", s.getSslTruststoreLocation());
                config.put("ssl.truststore.password", s.getSslTruststorePassword());
            }
            if (s.getEndpointIdentificationAlgorithm() != null) {
                config.put("ssl.endpoint.identification.algorithm", s.getEndpointIdentificationAlgorithm());
            }
        }
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> userSettlementLedgerKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        // Consumer가 어떤 설정으로 동작할지 지정
        f.setConsumerFactory(userSettlementLedgerConsumerFactory());
        // Batch Mode로 받고 싶은 경우
        f.setBatchListener(true);
        f.getContainerProperties().setAckMode(MANUAL_IMMEDIATE);
        // Prometheus/Grafana를 위한 메트릭 노출
        f.getContainerProperties().setObservationEnabled(true);
        return f;
    }

    @Bean
    public ConsumerFactory<String, String> settlementProcessConsumerFactory() {
        return setConsumerFactory(props.getConsumer().getCommonConfig(), props.getSecurity());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> settlementProcessKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(settlementProcessConsumerFactory());
        f.setBatchListener(true);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return f;
    }

}

