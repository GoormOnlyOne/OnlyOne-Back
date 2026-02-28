package com.example.onlyone.domain.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 알림 이벤트 버스 RabbitMQ 설정.
 * {@code app.notification.event-bus=rabbitmq} 일 때 활성화된다.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "rabbitmq")
public class NotificationRabbitConfig {

    public static final String EXCHANGE = "notification.exchange";
    public static final String QUEUE = "notification.created.queue";
    public static final String ROUTING_KEY = "notification.created";

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-message-ttl", 7 * 24 * 60 * 60 * 1000L)
                .build();
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange notificationExchange) {
        return BindingBuilder
                .bind(notificationQueue)
                .to(notificationExchange)
                .with(ROUTING_KEY);
    }

    @Bean("notificationRabbitMessageConverter")
    public MessageConverter notificationRabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean("notificationRabbitTemplate")
    public RabbitTemplate notificationRabbitTemplate(
            ConnectionFactory connectionFactory,
            MessageConverter notificationRabbitMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setExchange(EXCHANGE);
        template.setRoutingKey(ROUTING_KEY);
        template.setMessageConverter(notificationRabbitMessageConverter);
        return template;
    }
}
