package com.example.onlyone.domain.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB 알림 저장소 설정.
 * {@code app.notification.storage=mongodb} 일 때만 활성화된다.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.storage", havingValue = "mongodb")
@EnableMongoRepositories(basePackages = "com.example.onlyone.domain.notification.repository")
@EnableMongoAuditing
public class MongoNotificationConfig {
}
