package com.example.onlyone.domain.chat.config;

import com.example.onlyone.domain.chat.service.ChatSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RedisChatPubSubConfig {

    @Bean
    public RedisMessageListenerContainer chatListenerContainer(
            RedisConnectionFactory connectionFactory,
            ChatSubscriber chatSubscriber) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("redis-sub-");
        executor.initialize();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(executor);
        container.addMessageListener(chatSubscriber, new PatternTopic("chat.room.*"));
        return container;
    }
}
