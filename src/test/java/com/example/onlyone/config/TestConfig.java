package com.example.onlyone.config;

import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.mockito.Mockito.mock;

/**
 * 테스트용 설정
 * Firebase와 Redis 같은 외부 서비스만 Mock 처리
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
        // Redis Mock 연결
        return mock(LettuceConnectionFactory.class);
    }
    
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        
        // 실제 Redis 연결이 아니므로 afterPropertiesSet을 호출하지 않음
        return template;
    }
}