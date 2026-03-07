package com.example.onlyone.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 읽기/쓰기 커넥션 풀 분리 설정 (ec2 프로필 전용).
 * readOnly 트랜잭션은 읽기 전용 풀을 사용하여
 * 쓰기 작업의 커넥션 점유가 조회 API에 영향을 주지 않도록 격리한다.
 *
 * 활성화 조건: app.datasource.routing.enabled=true (ec2 yml에서 설정)
 */
@Configuration
@ConditionalOnProperty(name = "app.datasource.routing.enabled", havingValue = "true")
public class DataSourceRoutingConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties routingDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource writeDataSource(
            @Qualifier("routingDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("write-pool");
        return ds;
    }

    @Bean
    public HikariDataSource readDataSource(
            @Qualifier("routingDataSourceProperties") DataSourceProperties properties,
            @Qualifier("writeDataSource") HikariDataSource writeDs) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("read-pool");
        ds.setReadOnly(true);
        // 읽기 풀은 쓰기 풀과 동일 크기 — 고부하 시 조회 타임아웃 방지
        ds.setMaximumPoolSize(writeDs.getMaximumPoolSize());
        ds.setMinimumIdle(writeDs.getMinimumIdle());
        ds.setConnectionTimeout(writeDs.getConnectionTimeout());
        ds.setIdleTimeout(writeDs.getIdleTimeout());
        ds.setMaxLifetime(writeDs.getMaxLifetime());
        ds.setAutoCommit(writeDs.isAutoCommit());
        return ds;
    }

    @Bean
    public DataSource routingDataSource(@Qualifier("writeDataSource") DataSource writeDs,
                                        @Qualifier("readDataSource") DataSource readDs) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        ? "read" : "write";
            }
        };
        routing.setTargetDataSources(Map.of("write", writeDs, "read", readDs));
        routing.setDefaultTargetDataSource(writeDs);
        routing.afterPropertiesSet();
        return routing;
    }

    @Primary
    @Bean
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDs) {
        return new LazyConnectionDataSourceProxy(routingDs);
    }
}
