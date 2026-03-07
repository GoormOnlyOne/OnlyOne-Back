package com.example.onlyone.domain.settlement.event;

public record WalletCaptureFailedEvent(
        Long userSettlementId,
        Long memberWalletId,
        Long leaderWalletId,
        Long amount,
        Long memberBalanceBefore,
        Long leaderBalanceBefore
) {
}
