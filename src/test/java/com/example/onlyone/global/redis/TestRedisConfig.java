package com.example.onlyone.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@TestConfiguration
public class TestRedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port
    ) {
        var conf = new RedisStandaloneConfiguration(host, port);
        return new LettuceConnectionFactory(conf);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory cf) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);

        var str = new StringRedisSerializer();
        t.setKeySerializer(str);
//        t.setValueSerializer(str);
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        t.afterPropertiesSet();
        return t;
    }
}
