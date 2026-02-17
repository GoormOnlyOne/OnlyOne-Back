package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Wallet 원자성 연산을 @Retryable로 래핑하여
 * Optimistic Lock 충돌 시 자동 재시도하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletOperationService {

    private final WalletRepository walletRepository;

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int holdBalanceIfEnough(Long userId, long amount) {
        log.debug("holdBalanceIfEnough: userId={}, amount={}", userId, amount);
        return walletRepository.holdBalanceIfEnough(userId, amount);
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseHoldBalance(Long userId, long amount) {
        log.debug("releaseHoldBalance: userId={}, amount={}", userId, amount);
        return walletRepository.releaseHoldBalance(userId, amount);
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int captureHold(Long userId, long amount) {
        log.debug("captureHold: userId={}, amount={}", userId, amount);
        return walletRepository.captureHold(userId, amount);
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int creditByUserId(Long userId, long amount) {
        log.debug("creditByUserId: userId={}, amount={}", userId, amount);
        return walletRepository.creditByUserId(userId, amount);
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int batchReleaseHoldBalance(List<Long> userIds, long amount) {
        log.debug("batchReleaseHoldBalance: userIds={}, amount={}", userIds, amount);
        return walletRepository.batchReleaseHoldBalance(userIds, amount);
    }
}
