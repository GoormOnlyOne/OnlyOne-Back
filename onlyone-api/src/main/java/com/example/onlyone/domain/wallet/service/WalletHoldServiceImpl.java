package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletHoldServiceImpl implements WalletHoldService {

    private final WalletRepository walletRepository;

    @Override
    public void holdOrThrow(Long userId, long amount) {
        int updated = walletRepository.holdBalanceIfEnough(userId, amount);
        if (updated == 0) {
            throw new CustomException(FinanceErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }
    }

    @Override
    public void releaseOrThrow(Long userId, long amount) {
        int updated = walletRepository.releaseHoldBalance(userId, amount);
        if (updated == 0) {
            throw new CustomException(FinanceErrorCode.WALLET_HOLD_STATE_CONFLICT);
        }
    }

    @Override
    public void batchRelease(List<Long> userIds, long amount) {
        if (!userIds.isEmpty()) {
            walletRepository.batchReleaseHoldBalance(userIds, amount);
        }
    }
}
