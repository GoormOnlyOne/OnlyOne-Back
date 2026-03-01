package com.example.onlyone.domain.chat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * MongoDB 채팅 메시지 저장소 설정.
 * {@code app.chat.storage=mongodb} 일 때만 활성화.
 * {@code @EnableMongoAuditing}은 알림 도메인과 중복 등록 방지를 위해
 * 알림이 mysql일 때만 여기서 선언.
 */
@Configuration
@ConditionalOnProperty(name = "app.chat.storage", havingValue = "mongodb")
public class MongoChatConfig {

    @Configuration
    @ConditionalOnProperty(name = "app.notification.storage", havingValue = "mysql", matchIfMissing = true)
    @EnableMongoAuditing
    static class MongoAuditingFallback {
    }
}
