package com.example.onlyone.integration;

import com.example.onlyone.domain.wallet.service.RedisLuaService;
import com.example.onlyone.support.AbstractRedisContainerTest;
import com.example.onlyone.support.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Wallet Gate Lua 스크립트 통합 테스트.
 * 실제 Redis 컨테이너에서 분산 락(게이트) 동작을 검증한다.
 */
@SpringBootTest
@Import(IntegrationTestConfig.class)
@DisplayName("Redis Wallet Gate Lua 스크립트 통합 테스트")
class RedisWalletGateLuaTest extends AbstractRedisContainerTest {

    @Autowired
    private RedisLuaService redisLuaService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long USER_ID = 1L;
    private static final String OP = "capture";
    private static final int TTL_SEC = 5;

    @BeforeEach
    void setUp() {
        // 게이트 키 정리
        String gateKey = "wallet:gate:{" + USER_ID + "}:" + OP;
        stringRedisTemplate.delete(gateKey);
    }

    @Test
    @DisplayName("게이트 획득 성공시 owner 토큰이 반환된다")
    void acquireGate_returnsOwnerToken() {
        // when
        String owner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);

        // then
        assertThat(owner).isNotNull().isNotBlank();

        // Redis에 게이트 키가 존재하는지 확인
        String gateKey = "wallet:gate:{" + USER_ID + "}:" + OP;
        String storedOwner = stringRedisTemplate.opsForValue().get(gateKey);
        assertThat(storedOwner).isEqualTo(owner);
    }

    @Test
    @DisplayName("이미 잠긴 게이트는 획득에 실패한다")
    void acquireLockedGate_returnsNull() {
        // given: 먼저 게이트 획득
        String firstOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
        assertThat(firstOwner).isNotNull();

        // when: 다른 요청이 같은 게이트를 획득 시도
        String secondOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);

        // then: 실패하여 null 반환
        assertThat(secondOwner).isNull();
    }

    @Test
    @DisplayName("owner 토큰 일치시 게이트가 해제된다")
    void releaseGate_withMatchingOwner_deletesKey() {
        // given
        String owner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
        assertThat(owner).isNotNull();

        // when
        redisLuaService.releaseWalletGate(USER_ID, OP, owner);

        // then: 게이트가 해제되어 다시 획득 가능
        String newOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
        assertThat(newOwner).isNotNull();
    }

    @Test
    @DisplayName("TTL 만료후 게이트가 자동 해제된다")
    void gateExpires_afterTtl() throws InterruptedException {
        // given: TTL 1초로 게이트 획득
        String owner = redisLuaService.acquireWalletGate(USER_ID, OP, 1);
        assertThat(owner).isNotNull();

        // when: TTL 만료 대기
        Thread.sleep(1500);

        // then: 게이트가 만료되어 다시 획득 가능
        String newOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
        assertThat(newOwner).isNotNull();
    }

    @Test
    @DisplayName("withWalletGate로 감싸면 자동 해제된다")
    void withWalletGate_autoReleases() {
        // given & when
        AtomicBoolean executed = new AtomicBoolean(false);
        redisLuaService.withWalletGate(USER_ID, OP, TTL_SEC, () -> {
            executed.set(true);
            // 블록 내부에서는 게이트가 잠겨 있음
            String innerOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
            assertThat(innerOwner).isNull();
        });

        // then: 블록이 실행되었고
        assertThat(executed.get()).isTrue();

        // 블록 종료 후 게이트가 해제되어 다시 획득 가능
        String afterOwner = redisLuaService.acquireWalletGate(USER_ID, OP, TTL_SEC);
        assertThat(afterOwner).isNotNull();
    }
}
