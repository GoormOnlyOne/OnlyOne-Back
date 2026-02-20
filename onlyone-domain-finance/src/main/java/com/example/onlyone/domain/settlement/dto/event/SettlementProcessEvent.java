package com.example.onlyone.domain.settlement.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SettlementProcessEvent(
        String eventId,
        String occurredAt,
        Long settlementId,
        Long scheduleId,
        Long clubId,
        Long leaderId,
        Long leaderWalletId,
        Long costPerUser,
        Long totalAmount,
        List<Long> targetUserIds
) {
}
