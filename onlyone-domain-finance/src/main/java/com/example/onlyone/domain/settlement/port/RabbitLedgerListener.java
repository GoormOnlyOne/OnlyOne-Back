package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.config.rabbit.RabbitSettlementConfig;
import com.example.onlyone.domain.settlement.service.SettlementEventProcessor;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * RabbitMQ 원장 기록 리스너.
 * user-settlement.result.queue를 구독하여 {@link SettlementEventProcessor}에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "rabbitmq")
public class RabbitLedgerListener {

    private final SettlementEventProcessor processor;

    @RabbitListener(
            queues = RabbitSettlementConfig.USER_SETTLEMENT_RESULT_QUEUE,
            containerFactory = "rabbitSettlementListenerContainerFactory"
    )
    public void onUserSettlementResult(String payload, Channel channel,
                                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("RabbitMQ 원장 메시지 수신");
        try {
            processor.processLedgerPayloads(List.of(payload));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("RabbitMQ 원장 메시지 처리 실패", e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
