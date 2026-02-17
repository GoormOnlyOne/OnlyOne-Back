package com.example.onlyone.domain.settlement.dto.event;

import java.util.List;

public record SettlementProcessEvent(
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
