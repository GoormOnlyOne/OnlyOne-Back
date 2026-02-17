package com.example.onlyone.domain.settlement.dto.event;

public record WalletCaptureSucceededEvent(
        Long userSettlementId,
        Long memberWalletId,
        Long leaderWalletId,
        Long amount,
        Long memberBalanceAfter,
        Long leaderBalanceAfter
) {
}
