package com.example.onlyone.domain.settlement.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WalletCaptureSucceededEvent {
    private Long userSettlementId;
    private Long memberWalletId;
    private Long leaderWalletId;
    private int amount;
    private Long memberBalanceAfter;
    private Long leaderBalanceAfter;
}
