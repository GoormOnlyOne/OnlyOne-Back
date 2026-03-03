package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.event.OutboxEvent;

import java.util.List;

/**
 * Outbox 이벤트를 메시지 브로커로 발행하는 포트.
 * {@code app.settlement.message-broker} 프로퍼티로 구현체를 교체할 수 있다.
 * <p>
 * 구현체: Kafka (기본), RabbitMQ, Redis Streams
 */
public interface OutboxMessageRelay {

    /**
     * Outbox 이벤트 배치를 메시지 브로커로 발행한다.
     * 발행 성공 시 각 이벤트의 status를 PUBLISHED로 변경한다.
     */
    void publishBatch(List<OutboxEvent> events);

    /**
     * 단건 재시도 발행. 실패 시 예외를 던진다.
     */
    void publishSingle(OutboxEvent event);

    /**
     * 브로커 이름 (로깅/모니터링용).
     */
    String brokerName();
}
