package com.example.onlyone.domain.wallet.service;

import java.util.List;

/**
 * 지갑 Hold/Release 추상화 — Schedule 등 외부 도메인이 의존하는 계약
 */
public interface WalletHoldService {

    /** 잔액 홀드. 잔액 부족 시 CustomException(WALLET_BALANCE_NOT_ENOUGH) */
    void holdOrThrow(Long userId, long amount);

    /** 홀드 해제. 상태 불일치 시 CustomException(WALLET_HOLD_STATE_CONFLICT) */
    void releaseOrThrow(Long userId, long amount);

    /** 다수 사용자 홀드 배치 해제 (참여자가 있을 때만 호출) */
    void batchRelease(List<Long> userIds, long amount);
}
