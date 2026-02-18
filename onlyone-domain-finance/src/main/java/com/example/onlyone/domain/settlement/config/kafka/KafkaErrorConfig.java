package com.example.onlyone.domain.settlement.config.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaErrorConfig {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backoff = new FixedBackOff(5_000L, 3L); // 5s x 3회
        return new DefaultErrorHandler(recoverer, backoff);
    }
}
