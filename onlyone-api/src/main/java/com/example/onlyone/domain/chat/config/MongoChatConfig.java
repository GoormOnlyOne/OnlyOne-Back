package com.example.onlyone.domain.chat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * MongoDB 채팅 메시지 저장소 설정.
 * {@code app.chat.storage=mongodb} 일 때만 활성화.
 * {@code @EnableMongoAuditing}은 이 Config 자체에 선언하여,
 * chat.storage=mysql 일 때 MongoAuditingRegistrar가 실행되지 않도록 한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.chat.storage", havingValue = "mongodb")
@EnableMongoAuditing
public class MongoChatConfig {
}
