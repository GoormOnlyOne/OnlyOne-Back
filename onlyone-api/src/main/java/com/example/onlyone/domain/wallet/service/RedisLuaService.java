package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisLuaService implements WalletGateService {
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int RETRY_MIN_DELAY_MS = 5;
    private static final int RETRY_MAX_DELAY_MS = 20;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> walletGateAcquireScript;
    private final DefaultRedisScript<Long> walletGateReleaseScript;

    private static String gateKey(long userId, String op) {
        // 클러스터 슬롯 고정
        return "wallet:gate:{" + userId + "}:" + op;
    }

    /** 게이트 획득: 성공 시 owner 토큰, 실패 시 null */
    public String acquireWalletGate(long userId, String op, int ttlSec) {
        String key = gateKey(userId, op);
        String owner = UUID.randomUUID().toString();
        Long ok = redis.execute(walletGateAcquireScript, List.of(key),
                String.valueOf(ttlSec), owner);
        return (ok != null && ok == 1L) ? owner : null;
    }

    /** 게이트 해제: owner 일치할 때만 삭제 */
    public void releaseWalletGate(long userId, String op, String owner) {
        if (owner == null) return;
        String key = gateKey(userId, op);
        redis.execute(walletGateReleaseScript, List.of(key), owner);
    }

    /** 게이트 잡고 함수 실행 (+재시도) */
    @Override
    public <T> T withWalletGate(long userId, String op, int ttlSec, Supplier<T> body) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            String owner = acquireWalletGate(userId, op, ttlSec);
            if (owner != null) {
                try {
                    return body.get();
                } finally {
                    releaseWalletGate(userId, op, owner);
                }
            }
            // acquire 실패한 경우
            if (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(RETRY_MIN_DELAY_MS, RETRY_MAX_DELAY_MS));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CustomException(FinanceErrorCode.WALLET_OPERATION_IN_PROGRESS);
                }
            }
        }
        // 모든 시도 실패 시에만 예외
        throw new CustomException(FinanceErrorCode.WALLET_OPERATION_IN_PROGRESS);
    }


    /** void용 */
    @Override
    public void withWalletGate(long userId, String op, int ttlSec, Runnable body) {
        withWalletGate(userId, op, ttlSec, () -> { body.run(); return null; });
    }
}

