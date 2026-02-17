package com.example.onlyone.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

@ActiveProfiles("test")
public abstract class AbstractKafkaContainerTest {

    static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void overrideKafkaProps(DynamicPropertyRegistry r) {
        r.add("spring.kafka.enabled", () -> "true");
        r.add("spring.kafka.default-bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.producer.common-config.bootstrap-servers",
                () -> List.of(KAFKA.getBootstrapServers()));
        r.add("spring.kafka.consumer.common-config.bootstrap-servers",
                () -> List.of(KAFKA.getBootstrapServers()));
    }
}
