package com.example.onlyone.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
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

    @Value("${spring.data.redis.lettuce.pool.max-active:64}")
    private int poolMaxActive;
    @Value("${spring.data.redis.lettuce.pool.max-idle:32}")
    private int poolMaxIdle;
    @Value("${spring.data.redis.lettuce.pool.min-idle:8}")
    private int poolMinIdle;
    @Value("${spring.data.redis.lettuce.pool.max-wait:3000}")
    private long poolMaxWaitMs;
    @Value("${spring.data.redis.timeout:5000}")
    private long commandTimeoutMs;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        if (password == null || password.isBlank()) {
            if ("prod".equals(activeProfile) || "staging".equals(activeProfile)) {
                throw new IllegalStateException("Redis password must be set in production/staging environment");
            }
            log.warn("Redis password is not set. This is acceptable for local development only.");
        }

        // 풀 설정 — yml(spring.data.redis.lettuce.pool.*) 값 사용
        GenericObjectPoolConfig<StatefulConnection<?, ?>> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(poolMaxActive);
        pool.setMaxIdle(poolMaxIdle);
        pool.setMinIdle(poolMinIdle);
        pool.setMaxWait(Duration.ofMillis(poolMaxWaitMs));
        pool.setTestOnBorrow(true);           // 빌릴 때 연결 상태 검증
        pool.setTestWhileIdle(true);          // 유휴 연결 정리

        log.info("Redis pool: maxActive={}, maxIdle={}, minIdle={}, maxWait={}ms, commandTimeout={}ms",
                poolMaxActive, poolMaxIdle, poolMinIdle, poolMaxWaitMs, commandTimeoutMs);

        // Lettuce 클라이언트 옵션 — commandTimeout yml 연동 (기본 5초, 빠른 실패)
        LettuceClientConfiguration clientCfg =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig(pool)
                        .commandTimeout(Duration.ofMillis(commandTimeoutMs))
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
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 캐시 전용 ObjectMapper — @class 타입 정보 포함하여 Page 등 복잡 타입 역직렬화 지원
        ObjectMapper cacheMapper = new ObjectMapper();
        cacheMapper.registerModule(new JavaTimeModule());
        cacheMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        cacheMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        cacheMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        cacheMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer cacheSerializer = new GenericJackson2JsonRedisSerializer(cacheMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(cacheSerializer));

        var cacheConfigurations = new HashMap<String, RedisCacheConfiguration>();
        // 검색 — 클럽 데이터는 변경 빈도 낮음
        cacheConfigurations.put("teammatesClubs", defaultConfig.entryTtl(Duration.ofSeconds(120)));
        cacheConfigurations.put("recommendations", defaultConfig.entryTtl(Duration.ofSeconds(120)));
        cacheConfigurations.put("searchInterest", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("searchLocation", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("myClubs", defaultConfig.entryTtl(Duration.ofSeconds(60)));
        // 클럽 — 거의 안 변함
        cacheConfigurations.put("clubDetail", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        // 일정
        cacheConfigurations.put("scheduleList", defaultConfig.entryTtl(Duration.ofMinutes(2)));
        cacheConfigurations.put("scheduleDetail", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("scheduleUsers", defaultConfig.entryTtl(Duration.ofSeconds(60)));
        // 정산 — settlementList: @Cacheable 제거 (Page/DTO 역직렬화 문제)
        // 지갑
        cacheConfigurations.put("walletTxList", defaultConfig.entryTtl(Duration.ofSeconds(30)));
        // 피드 — feedList, feedComments: @Cacheable 제거 (Page/List<DTO> 역직렬화 문제)
        // 사용자 — CacheEvict 있어서 TTL은 백업용
        cacheConfigurations.put("userMyPage", defaultConfig.entryTtl(Duration.ofMinutes(2)));
        cacheConfigurations.put("userProfile", defaultConfig.entryTtl(Duration.ofMinutes(2)));
        // 채팅
        cacheConfigurations.put("chatRooms", defaultConfig.entryTtl(Duration.ofSeconds(60)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
