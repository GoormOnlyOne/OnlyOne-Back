package com.example.onlyone.domain.wallet.service;

import java.util.function.Supplier;

/**
 * 지갑 게이트 — 사용자별 동시성 제어 추상화.
 * 구현체(Redis Lua 등)에 대한 의존을 역전하여 도메인 서비스가 인프라에 의존하지 않도록 한다.
 */
public interface WalletGateService {

    /** 게이트를 획득하고 body를 실행한 뒤 해제한다 (반환값 있음) */
    <T> T withWalletGate(long userId, String op, int ttlSec, Supplier<T> body);

    /** 게이트를 획득하고 body를 실행한 뒤 해제한다 (void) */
    void withWalletGate(long userId, String op, int ttlSec, Runnable body);
}
