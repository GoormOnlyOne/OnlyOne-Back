package com.example.onlyone.domain.settlement.config.kafka;

import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.backoff.FixedBackOff;


@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class KafkaErrorConfig {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backoff = new FixedBackOff(1_000L, 2L); // 1s x 2회 (트랜지언트 에러만 재시도)
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(CustomException.class); // 비즈니스 예외 즉시 DLT
        return handler;
    }
}
