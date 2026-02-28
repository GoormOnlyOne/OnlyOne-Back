package com.example.onlyone.domain.notification.config;

import com.example.onlyone.domain.notification.port.RedisNotificationConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 알림 이벤트 버스 Redis Pub/Sub 설정.
 * {@code app.notification.event-bus=redis-pubsub} 일 때 활성화된다.
 * 기존 채팅 도메인의 Redis Pub/Sub 패턴을 참고.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "redis-pubsub")
public class RedisNotificationPubSubConfig {

    public static final String CHANNEL = "notification.created";

    @Bean
    public ChannelTopic notificationTopic() {
        return new ChannelTopic(CHANNEL);
    }

    @Bean("notificationRedisSubExecutor")
    public ThreadPoolTaskExecutor notificationRedisSubExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("noti-redis-sub-");
        exec.initialize();
        return exec;
    }

    @Bean
    public RedisMessageListenerContainer notificationRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisNotificationConsumer consumer,
            ChannelTopic notificationTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(consumer, notificationTopic);
        container.setTaskExecutor(notificationRedisSubExecutor());
        return container;
    }
}
