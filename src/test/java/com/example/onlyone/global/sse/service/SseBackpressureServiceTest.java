package com.example.onlyone.global.sse.service;

import com.example.onlyone.global.sse.metrics.SseMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("백프레셔 제어 서비스 테스트")
class SseBackpressureServiceTest {
    
    @Mock
    private SseMetrics sseMetrics;
    
    private SseBackpressureService backpressureService;
    
    @BeforeEach
    void setUp() {
        backpressureService = new SseBackpressureService(sseMetrics);
        // 테스트용 설정값 주입
        ReflectionTestUtils.setField(backpressureService, "maxConnections", 5);
        ReflectionTestUtils.setField(backpressureService, "maxMessagesPerSecond", 10);
        ReflectionTestUtils.setField(backpressureService, "userRateLimit", 3);
    }
    
    @Test
    @DisplayName("연결 한도 내에서 연결을 허용해야 한다")
    void shouldAllowConnectionWithinLimit() {
        // when
        boolean result = backpressureService.tryAcquireConnection();
        
        // then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("연결 한도 초과시 연결을 거부해야 한다")
    void shouldRejectConnectionWhenLimitExceeded() {
        // given - 최대 연결 수만큼 연결 생성
        for (int i = 0; i < 5; i++) {
            backpressureService.tryAcquireConnection();
        }
        
        // when - 한도 초과 연결 시도
        boolean result = backpressureService.tryAcquireConnection();
        
        // then
        assertThat(result).isFalse();
        verify(sseMetrics).recordConnectionRejected();
    }
    
    @Test
    @DisplayName("연결 해제시 새로운 연결이 가능해야 한다")
    void shouldAllowNewConnectionAfterRelease() {
        // given - 최대 연결 수만큼 연결 생성
        for (int i = 0; i < 5; i++) {
            backpressureService.tryAcquireConnection();
        }
        
        // when - 연결 해제 후 새 연결 시도
        backpressureService.releaseConnection();
        boolean result = backpressureService.tryAcquireConnection();
        
        // then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("메시지 전송 허용 여부를 올바르게 판단해야 한다")
    void shouldAllowMessageWithinRateLimit() {
        // given
        Long userId = 1L;
        
        // when
        boolean result = backpressureService.tryAcquireMessagePermit(userId);
        
        // then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("사용자별 레이트 리미트를 적용해야 한다")
    void shouldApplyUserRateLimit() {
        // given
        Long userId = 1L;
        
        // when - 사용자 레이트 리미트(3)를 초과하여 요청
        boolean firstResult = backpressureService.tryAcquireMessagePermit(userId);
        boolean secondResult = backpressureService.tryAcquireMessagePermit(userId);
        boolean thirdResult = backpressureService.tryAcquireMessagePermit(userId);
        boolean fourthResult = backpressureService.tryAcquireMessagePermit(userId);
        
        // then
        assertThat(firstResult).isTrue();
        assertThat(secondResult).isTrue();
        assertThat(thirdResult).isTrue();
        assertThat(fourthResult).isFalse(); // 레이트 리미트 초과
    }
    
    @Test
    @DisplayName("글로벌 메시지 레이트 리미트를 적용해야 한다")
    void shouldApplyGlobalRateLimit() {
        // given - 글로벌 레이트 리미트(10)까지 메시지 전송
        for (int i = 0; i < 10; i++) {
            backpressureService.tryAcquireMessagePermit((long) i);
        }
        
        // when - 글로벌 리미트 초과 시도
        boolean result = backpressureService.tryAcquireMessagePermit(999L);
        
        // then
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("다른 사용자는 독립적인 레이트 리미트를 가져야 한다")
    void shouldHaveIndependentRateLimitPerUser() {
        // given
        Long userId1 = 1L;
        Long userId2 = 2L;
        
        // when - 사용자1의 레이트 리미트 소진
        for (int i = 0; i < 3; i++) {
            backpressureService.tryAcquireMessagePermit(userId1);
        }
        
        boolean user1Blocked = backpressureService.tryAcquireMessagePermit(userId1);
        boolean user2Allowed = backpressureService.tryAcquireMessagePermit(userId2);
        
        // then
        assertThat(user1Blocked).isFalse(); // 사용자1은 차단
        assertThat(user2Allowed).isTrue();  // 사용자2는 허용
    }
    
    @Test
    @DisplayName("시스템 부하 상태를 올바르게 계산해야 한다")
    void shouldCalculateSystemLoadCorrectly() {
        // given - 연결 없음
        SseBackpressureService.SystemLoadStatus lowLoad = backpressureService.getSystemLoad();
        
        // when - 일부 연결 생성 (5개 중 3개 = 60%)
        for (int i = 0; i < 3; i++) {
            backpressureService.tryAcquireConnection();
        }
        SseBackpressureService.SystemLoadStatus mediumLoad = backpressureService.getSystemLoad();
        
        // 모든 연결 생성 (5개 중 5개 = 100%)
        for (int i = 0; i < 2; i++) {
            backpressureService.tryAcquireConnection();
        }
        SseBackpressureService.SystemLoadStatus highLoad = backpressureService.getSystemLoad();
        
        // then
        assertThat(lowLoad).isEqualTo(SseBackpressureService.SystemLoadStatus.LOW);
        assertThat(mediumLoad).isEqualTo(SseBackpressureService.SystemLoadStatus.MEDIUM);
        assertThat(highLoad).isEqualTo(SseBackpressureService.SystemLoadStatus.HIGH);
    }
    
    @Test
    @DisplayName("성능 기반 적응형 제한 조정이 작동해야 한다")
    void shouldAdjustLimitsBasedOnPerformance() {
        // given
        int initialMessageLimit = getMaxMessagesPerSecond();
        
        // when - 성능 저하 상황 시뮬레이션
        backpressureService.adjustLimitsBasedOnPerformance(2000.0, 0.1); // 높은 지연시간, 높은 에러율
        int decreasedLimit = getMaxMessagesPerSecond();
        
        // 성능 향상 상황 시뮬레이션
        backpressureService.adjustLimitsBasedOnPerformance(50.0, 0.001); // 낮은 지연시간, 낮은 에러율
        int increasedLimit = getMaxMessagesPerSecond();
        
        // then
        assertThat(decreasedLimit).isLessThan(initialMessageLimit);
        assertThat(increasedLimit).isGreaterThan(decreasedLimit);
    }
    
    @Test
    @DisplayName("동시 연결 요청을 안전하게 처리해야 한다")
    void shouldHandleConcurrentConnectionRequests() throws InterruptedException {
        // given
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        int[] successCount = {0};
        int[] failureCount = {0};
        
        // when - 동시에 여러 스레드에서 연결 요청
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (backpressureService.tryAcquireConnection()) {
                        synchronized (successCount) {
                            successCount[0]++;
                        }
                    } else {
                        synchronized (failureCount) {
                            failureCount[0]++;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // then
        latch.await(5, TimeUnit.SECONDS);
        
        // 최대 연결 수(5)만큼만 성공하고 나머지는 실패해야 함
        assertThat(successCount[0]).isEqualTo(5);
        assertThat(failureCount[0]).isEqualTo(5);
        
        executor.shutdown();
    }
    
    @Test
    @DisplayName("토큰 버킷 레이트 리미터가 시간에 따라 토큰을 리필해야 한다")
    void shouldRefillTokensOverTime() throws InterruptedException {
        // given
        Long userId = 1L;
        
        // 사용자 레이트 리미트 소진
        for (int i = 0; i < 3; i++) {
            backpressureService.tryAcquireMessagePermit(userId);
        }
        
        // 리미트 초과 확인
        boolean blocked = backpressureService.tryAcquireMessagePermit(userId);
        assertThat(blocked).isFalse();
        
        // when - 1초 대기 (토큰 리필 시간)
        Thread.sleep(1100);
        
        // then - 토큰이 리필되어 다시 사용 가능해야 함
        boolean allowed = backpressureService.tryAcquireMessagePermit(userId);
        assertThat(allowed).isTrue();
    }
    
    private int getMaxMessagesPerSecond() {
        return (int) ReflectionTestUtils.getField(backpressureService, "maxMessagesPerSecond");
    }
}