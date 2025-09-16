package com.example.onlyone.domain.settlement.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SettlementProcessEvent {
    private final Long settlementId;
    private final Long scheduleId;
    private final Long clubId;
    private final Long leaderId;
    private final Long leaderWalletId;
    private final Long costPerUser;
    private final Long totalAmount;
    private final List<Long> targetUserIds;
}
