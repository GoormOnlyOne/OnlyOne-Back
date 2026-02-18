package com.example.onlyone.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;

@Slf4j
@Configuration
@EnableCaching
@Profile("!test")
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.password:}")
    private String password;
    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    /**
     * ObjectMapper with JavaTimeModule for LocalDateTime serialization
     * Primary Bean으로 등록하여 모든 Redis 직렬화에서 사용
     */
    @Bean(name = "redisObjectMapper")
    @Primary
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 알 수 없는 속성 무시 (역직렬화 안정성)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        if (password == null || password.isBlank()) {
            if ("prod".equals(activeProfile) || "staging".equals(activeProfile)) {
                throw new IllegalStateException("Redis password must be set in production/staging environment");
            }
            log.warn("Redis password is not set. This is acceptable for local development only.");
        }

        // 풀 설정 (Pub/Sub + Cache 병행을 위해 확장)
        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(128);  // 64 → 128 (Pub/Sub 별도 연결)
        pool.setMaxIdle(64);    // 32 → 64
        pool.setMinIdle(32);    // 16 → 32

        // Lettuce 클라이언트 옵션 (BLOCK 10s보다 크게)
        LettuceClientConfiguration clientCfg =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig((GenericObjectPoolConfig<StatefulConnection<?, ?>>) pool)
                        .commandTimeout(Duration.ofSeconds(15))
                        .clientOptions(ClientOptions.builder()
                                .autoReconnect(true)
                                .pingBeforeActivateConnection(true)
                                .build())
                        .build();

        // 서버 설정
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            server.setPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(server, clientCfg);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper redisObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        // Jackson2JsonRedisSerializer with custom ObjectMapper
        Jackson2JsonRedisSerializer<Object> serializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    /**
     * Redis 캐시 매니저 (부하 테스트 최적화)
     * - 알림 목록: 5초 TTL
     * - 읽지 않은 개수: 10초 TTL
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper redisObjectMapper) {
        // Jackson2JsonRedisSerializer with custom ObjectMapper
        Jackson2JsonRedisSerializer<Object> serializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        var cacheConfigurations = new HashMap<String, RedisCacheConfiguration>();
        cacheConfigurations.put("notificationList", defaultConfig.entryTtl(Duration.ofSeconds(30)));
        cacheConfigurations.put("unreadCount", defaultConfig.entryTtl(Duration.ofSeconds(60)));
        cacheConfigurations.put("teammatesClubs", defaultConfig.entryTtl(Duration.ofSeconds(120)));
        cacheConfigurations.put("recommendations", defaultConfig.entryTtl(Duration.ofSeconds(120)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
