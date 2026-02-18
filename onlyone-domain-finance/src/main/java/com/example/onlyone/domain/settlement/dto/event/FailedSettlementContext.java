package com.example.onlyone.domain.settlement.dto.event;

/**
 * 정산 실패 시 Outbox 이벤트 기록에 필요한 컨텍스트
 */
public record FailedSettlementContext(
        Long settlementId,
        Long userSettlementId,
        Long participantId,
        Long memberWalletId,
        Long leaderId,
        Long leaderWalletId,
        Long amount
) {}
