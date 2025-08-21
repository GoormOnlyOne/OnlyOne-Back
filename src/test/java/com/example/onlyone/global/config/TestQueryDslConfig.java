package com.example.onlyone.global.config;

import com.example.onlyone.domain.notification.repository.NotificationRepositoryImpl;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestQueryDslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }

    @Bean
    public NotificationRepositoryImpl notificationRepositoryImpl(JPAQueryFactory queryFactory) {
        return new NotificationRepositoryImpl(queryFactory);
    }
}