package com.example.onlyone.domain.settlement.config.rabbit;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 정산 도메인 설정.
 * {@code app.settlement.message-broker=rabbitmq} 일 때 활성화된다.
 *
 * <p>Exchange: settlement.exchange (direct)
 * <p>Routing keys: settlement.process, user-settlement.result
 * <p>DLQ: *.dlq (dead letter exchange)
 */
@Configuration
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "rabbitmq")
public class RabbitSettlementConfig {

    // ========== Exchange ==========
    public static final String EXCHANGE = "settlement.exchange";
    public static final String DLX_EXCHANGE = "settlement.dlx";

    // ========== Routing Keys ==========
    public static final String SETTLEMENT_PROCESS_RK = "settlement.process";
    public static final String USER_SETTLEMENT_RESULT_RK = "user-settlement.result";

    // ========== Queues ==========
    public static final String SETTLEMENT_PROCESS_QUEUE = "settlement.process.queue";
    public static final String USER_SETTLEMENT_RESULT_QUEUE = "user-settlement.result.queue";
    public static final String SETTLEMENT_PROCESS_DLQ = "settlement.process.dlq";
    public static final String USER_SETTLEMENT_RESULT_DLQ = "user-settlement.result.dlq";

    // ── Exchange ──
    @Bean
    public DirectExchange settlementExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange settlementDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ── Queues ──
    @Bean
    public Queue settlementProcessQueue() {
        return QueueBuilder.durable(SETTLEMENT_PROCESS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", SETTLEMENT_PROCESS_RK)
                .build();
    }

    @Bean
    public Queue userSettlementResultQueue() {
        return QueueBuilder.durable(USER_SETTLEMENT_RESULT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", USER_SETTLEMENT_RESULT_RK)
                .build();
    }

    @Bean
    public Queue settlementProcessDlq() {
        return QueueBuilder.durable(SETTLEMENT_PROCESS_DLQ).build();
    }

    @Bean
    public Queue userSettlementResultDlq() {
        return QueueBuilder.durable(USER_SETTLEMENT_RESULT_DLQ).build();
    }

    // ── Bindings ──
    @Bean
    public Binding settlementProcessBinding() {
        return BindingBuilder.bind(settlementProcessQueue())
                .to(settlementExchange())
                .with(SETTLEMENT_PROCESS_RK);
    }

    @Bean
    public Binding userSettlementResultBinding() {
        return BindingBuilder.bind(userSettlementResultQueue())
                .to(settlementExchange())
                .with(USER_SETTLEMENT_RESULT_RK);
    }

    @Bean
    public Binding settlementProcessDlqBinding() {
        return BindingBuilder.bind(settlementProcessDlq())
                .to(settlementDlxExchange())
                .with(SETTLEMENT_PROCESS_RK);
    }

    @Bean
    public Binding userSettlementResultDlqBinding() {
        return BindingBuilder.bind(userSettlementResultDlq())
                .to(settlementDlxExchange())
                .with(USER_SETTLEMENT_RESULT_RK);
    }

    // ── Template ──
    @Bean
    public RabbitTemplate settlementRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setExchange(EXCHANGE);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // publisher confirm 실패 로깅
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("RabbitMQ publisher confirm failed: {}", cause);
            }
        });
        return template;
    }

    // ── Listener Container Factory ──
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitSettlementListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(50);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}
