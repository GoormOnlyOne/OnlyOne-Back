package com.example.onlyone.config;

import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 테스트용 설정
 * Firebase와 Redis를 Mock으로 처리
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public FirebaseMessaging firebaseMessaging() {
        // Firebase는 실제 초기화가 어려우므로 Mock만 사용
        return mock(FirebaseMessaging.class);
    }
    
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        // Mock Redis 연결 팩토리
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        return connectionFactory;
    }
    
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate() {
        // Mock Redis 템플릿
        return mock(RedisTemplate.class);
    }
    
    @Bean
    @Primary
    public CacheManager cacheManager() {
        // Mock 캐시 매니저
        return mock(CacheManager.class);
    }
    
    @Bean
    @Primary
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        // Mock JWT 필터로 인증 처리를 우회
        return mock(JwtAuthenticationFilter.class);
    }
}