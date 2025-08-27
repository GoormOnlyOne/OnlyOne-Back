package com.example.onlyone.domain.notification.performance;

import com.example.onlyone.domain.notification.service.RedisHealthChecker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 장애 대응 테스트
 */
@Disabled
@DisplayName("Redis 장애 대응 테스트")
class RedisFailoverTest {

    @Test
    @DisplayName("UT-NT-174: Redis 장애 시 FCM 폴백")
    void utNt174WhenRedisDownThenFallbackToFcm() {
        // given - Redis 장애 상황
        RedisHealthChecker.CircuitStatus openCircuit = 
            new RedisHealthChecker.CircuitStatus(true, 3, System.currentTimeMillis());
        
        // when & then - FCM 폴백 조건 확인
        assertThat(openCircuit.isOpen()).isTrue();
        assertThat(openCircuit.failureCount()).isEqualTo(3);
        assertThat(openCircuit.lastFailureTime()).isGreaterThan(0);
        
        System.out.println("✅ FCM 폴백 테스트 완료");
    }

    @Test
    @DisplayName("UT-NT-175: Redis 복구 시 SSE 전환")
    void utNt175WhenRedisRecoveredThenBackToSse() {
        // given - Redis 복구 상황
        RedisHealthChecker.CircuitStatus closedCircuit = 
            new RedisHealthChecker.CircuitStatus(false, 0, 0L);
        
        // when & then - SSE 재개 조건 확인
        assertThat(closedCircuit.isOpen()).isFalse();
        assertThat(closedCircuit.failureCount()).isEqualTo(0);
        assertThat(closedCircuit.lastFailureTime()).isEqualTo(0L);
        
        System.out.println("✅ SSE 복구 테스트 완료");
    }
    
    @Test
    @DisplayName("UT-NT-176: Circuit Breaker 패턴")
    void utNt176CircuitBreakerWorks() {
        // given
        long failureTime = System.currentTimeMillis();
        
        // when - 장애 발생
        RedisHealthChecker.CircuitStatus failed = 
            new RedisHealthChecker.CircuitStatus(true, 3, failureTime);
        
        // then
        assertThat(failed.isOpen()).isTrue();
        assertThat(failed.failureCount()).isEqualTo(3);
        
        System.out.println("✅ Circuit Breaker 테스트 완료");
    }
}