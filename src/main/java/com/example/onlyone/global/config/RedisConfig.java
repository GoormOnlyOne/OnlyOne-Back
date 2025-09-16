package com.example.onlyone.global.config;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import com.example.onlyone.domain.chat.service.ChatSubscriber;
import lombok.RequiredArgsConstructor;
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
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
@Profile("!test")
@RequiredArgsConstructor
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.password}")
    private String password;

    private final ChatSubscriber chatSubscriber;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 풀 설정
        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(64);
        pool.setMaxIdle(32);
        pool.setMinIdle(16);

        // Lettuce 클라이언트 옵션 (BLOCK 10s보다 크게)
        LettuceClientConfiguration clientCfg =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig((GenericObjectPoolConfig<StatefulConnection<?, ?>>) pool)
                        .commandTimeout(Duration.ofSeconds(15))
                        .clientOptions(io.lettuce.core.ClientOptions.builder()
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

    // Pub/Sub 발행용 StringRedisTemplate
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    // Pub/Sub 수신용 ListenerContainer
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 테스트용: 채팅방 98980번 구독
        container.addMessageListener(chatSubscriber, new PatternTopic("chat.room.*"));

        return container;
    }
}
