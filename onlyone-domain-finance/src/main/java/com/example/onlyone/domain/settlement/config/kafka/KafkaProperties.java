package com.example.onlyone.domain.settlement.config.kafka;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Data
@Component
@ConfigurationProperties(prefix = "spring.kafka")
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "kafka", matchIfMissing = true)
public class KafkaProperties {

    private String defaultBootstrapServers;
    private Consumer consumer = new Consumer();
    private Producer producer = new Producer();
    private Security security = new Security();

    @Data
    public static class Consumer {
        private ConsumerCommonConfig commonConfig = new ConsumerCommonConfig();
        private ConsumerConfig userSettlementLedgerConsumerConfig = new ConsumerConfig();
    }

    @Data
    public static class Producer {
        private ProducerCommonConfig commonConfig = new ProducerCommonConfig();
        private ProducerConfig settlementProcessProducerConfig = new ProducerConfig();
        // 필요 시 다른 프로듀서 config 추가
    }

    @Data
    public static class ConsumerCommonConfig {
        private String groupId;
        private String clientId;
        private List<String> bootstrapServers;
        private String timeoutMs;
        private int fetchMinBytes;
        private int fetchMaxWaitMs;
    }

    @Data
    public static class ConsumerConfig {
        private String topic;
    }

    @Data
    public static class ProducerCommonConfig {
        private String clientId;
        private List<String> bootstrapServers;
        private String transactionalIdPrefix;
        private String acks;
        private Integer lingerMs;
        private Integer batchSize;
    }

    @Data
    public static class ProducerConfig {
        private String topic;
    }

    @Data
    public static class Security {
        private boolean enabled;
        private String protocol; // SASL_SSL, etc.
        private String mechanism; // PLAIN, SCRAM-SHA-512, etc.
        private String jaas;      // JAAS config string
        private String sslTruststoreLocation;
        private String sslTruststorePassword;
        private String endpointIdentificationAlgorithm; // https
    }
}

