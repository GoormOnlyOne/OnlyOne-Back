package com.example.onlyone.global.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

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

    // @Bean
    // public RedisConnectionFactory redisConnectionFactory() {
    //     LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
    //     factory.setPassword(password);
    //     return factory;
    // }

    /** 풀링 적용된 LettuceConnectionFactory (우선사용 원하면 @Primary) */
    @Bean
    @Primary
    public LettuceConnectionFactory redisConnectionFactory() {
        // 1) 풀 설정
        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(64);
        pool.setMaxIdle(32);
        pool.setMinIdle(8);

        // 2) 클라이언트 옵션 + 타임아웃(Streams BLOCK보다 길게)
        LettuceClientConfiguration clientCfg =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig((GenericObjectPoolConfig<StatefulConnection<?, ?>>) pool)
                        .commandTimeout(Duration.ofSeconds(15)) // ← BLOCK(10s)보다 충분히 크게
                        .clientOptions(io.lettuce.core.ClientOptions.builder()
                                .autoReconnect(true)                        // 네트워크 단절시 자동 복구
                                .pingBeforeActivateConnection(true)         // 죽은 커넥션 조기 감지
                                .build())
                        .build();

        // 3) 서버 설정
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            server.setPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(server, clientCfg);
    }

    // redis template를 사용하여 redis에 직접 데이터를 저장하고 조회
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public DefaultRedisScript<List> likeToggleScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/like_toggle.lua"));
        script.setResultType(List.class); // EVAL의 MULTI 결과를 List로 받음
        return script;
    }
}
