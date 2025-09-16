package com.example.onlyone.global.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Slf4j
@Configuration
@EnableCaching
@Profile("!test")
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.password}")
    private String password;

    /**
     * Lettuce Client Resources 설정 - 극한 성능 최적화
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(32)           // I/O 스레드 대폭 증가
                .computationThreadPoolSize(16)  // 연산 스레드 대폭 증가
                .build();
    }

    /**
     * Lettuce Client 설정
     */
    @Bean
    public LettuceClientConfiguration lettuceClientConfiguration(ClientResources clientResources) {
        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .publishOnScheduler(true)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .keepAlive(true)
                        .tcpNoDelay(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(10)))
                .build();

        return LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .clientResources(clientResources)
                .commandTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 기본 Redis Connection Factory (동기용)
     */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory(LettuceClientConfiguration clientConfig) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port),
                clientConfig
        );
        
        if (password != null && !password.isEmpty()) {
            factory.getStandaloneConfiguration().setPassword(password);
        }
        
        log.info("Redis Connection Factory 초기화: {}:{}", host, port);
        return factory;
    }

    /**
     * 기본 Redis Template (캐시용)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
