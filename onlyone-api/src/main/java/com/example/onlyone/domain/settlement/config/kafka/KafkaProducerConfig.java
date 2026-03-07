package com.example.onlyone.domain.settlement.config.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@RequiredArgsConstructor
@Configuration
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class KafkaProducerConfig {

    private final KafkaProperties props;

    @Bean
    public ProducerFactory<String, String> ledgerProducerFactory() {
        KafkaProperties.ProducerCommonConfig producer = props.getProducer().getCommonConfig();
        KafkaProperties.Security security = props.getSecurity();

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, producer.getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, producer.getClientId());
        config.put(ProducerConfig.ACKS_CONFIG, producer.getAcks());
        config.put(ProducerConfig.LINGER_MS_CONFIG, producer.getLingerMs());
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, producer.getBatchSize());
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // 보안
        if (security != null && security.isEnabled()) {
            config.put("security.protocol", security.getProtocol());
            config.put("sasl.mechanism", security.getMechanism());
            config.put("sasl.jaas.config", security.getJaas());
            if (security.getSslTruststoreLocation() != null && !security.getSslTruststoreLocation().isBlank()) {
                config.put("ssl.truststore.location", security.getSslTruststoreLocation());
                config.put("ssl.truststore.password", security.getSslTruststorePassword());
            }
        }
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(config);
        pf.setTransactionIdPrefix(props.getProducer().getCommonConfig().getTransactionalIdPrefix());
        return pf;
    }

    @Bean
    public KafkaTemplate<String, String> ledgerKafkaTemplate() {
        return new KafkaTemplate<>(ledgerProducerFactory());
    }
}
