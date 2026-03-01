package com.example.onlyone.domain.chat.config;

import com.example.onlyone.domain.chat.service.ChatSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RedisChatPubSubConfig {

    @Bean
    public RedisMessageListenerContainer chatListenerContainer(
            RedisConnectionFactory connectionFactory,
            ChatSubscriber chatSubscriber) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(128);
        executor.setMaxPoolSize(400);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("redis-sub-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(executor);
        container.addMessageListener(chatSubscriber, new PatternTopic("chat.room.*"));
        return container;
    }
}
