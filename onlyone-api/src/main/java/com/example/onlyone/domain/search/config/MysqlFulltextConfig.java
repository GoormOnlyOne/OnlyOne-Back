package com.example.onlyone.domain.search.config;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.engine", havingValue = "mysql")
public class MysqlFulltextConfig {

    private final EntityManager entityManager;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createFulltextIndex() {
        try {
            // 인덱스 존재 여부 확인
            @SuppressWarnings("unchecked")
            var existing = entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'club' " +
                    "AND INDEX_NAME = 'ft_club_search'"
            ).getSingleResult();

            if (((Number) existing).intValue() > 0) {
                log.info("[SearchConfig] FULLTEXT index 'ft_club_search' already exists");
                return;
            }

            entityManager.createNativeQuery(
                    "ALTER TABLE club ADD FULLTEXT INDEX ft_club_search (name, description) WITH PARSER ngram"
            ).executeUpdate();

            log.info("[SearchConfig] FULLTEXT index 'ft_club_search' created (ngram parser)");
        } catch (Exception e) {
            log.warn("[SearchConfig] FULLTEXT index creation failed (may already exist): {}", e.getMessage());
        }
    }
}
