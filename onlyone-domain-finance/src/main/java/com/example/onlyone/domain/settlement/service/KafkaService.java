package com.example.onlyone.domain.settlement.service;

/**
 * @deprecated Kafka 리스너가 {@link com.example.onlyone.domain.settlement.port.KafkaLedgerListener}로 이동됨.
 * 비즈니스 로직은 {@link SettlementEventProcessor} → {@link LedgerWriter}에 위임됨.
 * 이 클래스는 하위 호환성을 위해 유지되며 다음 정리 시 제거 예정.
 */
@Deprecated(forRemoval = true)
public class KafkaService {
    // 모든 로직이 SettlementEventProcessor + KafkaLedgerListener로 이동
}
