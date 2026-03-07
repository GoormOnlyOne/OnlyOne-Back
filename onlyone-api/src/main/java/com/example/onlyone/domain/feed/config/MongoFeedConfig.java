package com.example.onlyone.domain.feed.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@ConditionalOnProperty(name = "app.feed.storage", havingValue = "mongodb")
@EnableMongoRepositories(basePackages = "com.example.onlyone.domain.feed")
public class MongoFeedConfig {
}
