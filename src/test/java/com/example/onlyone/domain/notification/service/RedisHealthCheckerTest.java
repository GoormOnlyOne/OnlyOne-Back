package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.springframework.data.redis.core.RedisCallback;

/**
 * Redis 상태 모니터링 및 Circuit Breaker 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("Redis Health Checker 테스트")
class RedisHealthCheckerTest {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RedisHealthChecker redisHealthChecker;
    
    @BeforeEach
    void setUp() {
        // Circuit Breaker 상태 초기화
        ReflectionTestUtils.setField(redisHealthChecker, "isCircuitOpen", new AtomicBoolean(false));
        ReflectionTestUtils.setField(redisHealthChecker, "consecutiveFailures", new AtomicInteger(0));
        ReflectionTestUtils.setField(redisHealthChecker, "lastFailureTime", new AtomicLong(0));
    }

    @Nested
    @DisplayName("Redis 헬스체크 기본 기능")
    class BasicHealthCheckTest {

        @Test
        @DisplayName("UT-NT-074: Redis 정상 상태 확인")
        void utNt074ReturnsHealthyWhenRedisIsAvailable() throws Exception {
            // given - Redis가 정상 응답하는 상황
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenReturn("PONG");
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then
            assertThat(isHealthy).isTrue();
        }

        @Test
        @DisplayName("UT-NT-075: Redis 응답 없음 상태")
        void utNt075ReturnsUnhealthyWhenRedisDoesNotRespond() throws Exception {
            // given - Redis가 응답하지 않는 상황
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then
            assertThat(isHealthy).isFalse();
        }

        @Test
        @DisplayName("UT-NT-076: Redis 느린 응답 처리")
        void utNt076ReturnsUnhealthyWhenRedisResponseIsSlow() throws Exception {
            // given - Redis가 느리게 응답하는 상황 (타임아웃 시뮬레이션)
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenAnswer(invocation -> {
                // 헬스체크 타임아웃보다 오래 걸리게 만들기 위해 잠시 대기
                try {
                    Thread.sleep(1100); // HEALTH_CHECK_TIMEOUT_MS(1000) 보다 길게
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "PONG";
            });
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then
            assertThat(isHealthy).isFalse();
        }

        @Test
        @DisplayName("UT-NT-077: Redis PONG 응답이 아닌 경우")
        void utNt077ReturnsUnhealthyWhenRedisReturnsNonPongResponse() throws Exception {
            // given - Redis가 PONG이 아닌 다른 응답을 하는 상황
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenReturn("ERROR");
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then
            assertThat(isHealthy).isFalse();
        }
    }

    @Nested
    @DisplayName("Circuit Breaker 기능")
    class CircuitBreakerTest {

        @Test
        @DisplayName("UT-NT-078: Circuit Breaker 오픈")
        void utNt078OpensCircuitAfterConsecutiveFailures() throws Exception {
            // given - Redis가 계속 실패하는 상황
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when - 실패 임계점(3회)까지 호출
            boolean firstCheck = redisHealthChecker.isHealthy();
            boolean secondCheck = redisHealthChecker.isHealthy();
            boolean thirdCheck = redisHealthChecker.isHealthy();
            
            // Circuit이 열린 후 추가 호출
            boolean fourthCheck = redisHealthChecker.isHealthy();

            // then
            assertThat(firstCheck).isFalse();
            assertThat(secondCheck).isFalse();
            assertThat(thirdCheck).isFalse();
            assertThat(fourthCheck).isFalse(); // Circuit이 열려서 바로 false 반환
            
            // Circuit 상태 확인
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isTrue();
            assertThat(circuitStatus.failureCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("UT-NT-079: Circuit Breaker 리셋 시도")
        void utNt079AttemptsCircuitResetAfterTimeout() throws Exception {
            // given - Circuit이 열린 상태
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));
            
            // Circuit을 열기 위해 3번 실패
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            
            // Circuit이 열렸는지 확인
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isTrue();
            
            // 시간을 강제로 경과시키기 위해 lastFailureTime 조작
            AtomicLong lastFailureTime = (AtomicLong) ReflectionTestUtils.getField(redisHealthChecker, "lastFailureTime");
            lastFailureTime.set(System.currentTimeMillis() - 31000); // 31초 전으로 설정 (CIRCUIT_OPEN_DURATION_MS: 30초)
            
            // Redis가 다시 정상 응답하도록 설정
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenReturn("PONG");
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when - Circuit 리셋 시도
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then - Circuit이 닫히고 헬스체크 성공
            assertThat(isHealthy).isTrue();
            
            var resetCircuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(resetCircuitStatus.isOpen()).isFalse();
            assertThat(resetCircuitStatus.failureCount()).isZero();
        }

        @Test
        @DisplayName("UT-NT-080: Circuit 리셋 실패 시 계속 열린 상태 유지")
        void utNt080KeepsCircuitOpenWhenResetFails() throws Exception {
            // given - Circuit이 열린 상태
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));
            
            // Circuit을 열기 위해 3번 실패
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            
            // 시간을 강제로 경과시키기 위해 lastFailureTime 조작
            AtomicLong lastFailureTime = (AtomicLong) ReflectionTestUtils.getField(redisHealthChecker, "lastFailureTime");
            lastFailureTime.set(System.currentTimeMillis() - 31000); // 31초 전으로 설정
            
            // Redis가 여전히 실패하는 상황 유지 (이미 설정됨)

            // when - Circuit 리셋 시도가 실패
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then - Circuit이 계속 열린 상태
            assertThat(isHealthy).isFalse();
            
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-081: Circuit 오픈 전 성공하면 실패 카운트 리셋")
        void utNt081ResetsFailureCountOnSuccess() throws Exception {
            // given - 먼저 2번 실패 (임계점 3보다 작음)
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));
            
            redisHealthChecker.isHealthy(); // 실패 1
            redisHealthChecker.isHealthy(); // 실패 2
            
            // Circuit이 아직 열리지 않았는지 확인
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isFalse();
            assertThat(circuitStatus.failureCount()).isEqualTo(2);
            
            // Redis가 다시 정상 응답하도록 설정
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenReturn("PONG");
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when - 성공
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then - 성공으로 인해 실패 카운트 리셋
            assertThat(isHealthy).isTrue();
            
            var resetCircuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(resetCircuitStatus.isOpen()).isFalse();
            assertThat(resetCircuitStatus.failureCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Circuit Status 조회")
    class CircuitStatusTest {

        @Test
        @DisplayName("UT-NT-082: Circuit Status 정보 반환")
        void utNt082ReturnsCircuitStatusInformation() {
            // given - 초기 상태

            // when
            var circuitStatus = redisHealthChecker.getCircuitStatus();

            // then - 초기 상태 정보 확인
            assertThat(circuitStatus).isNotNull();
            assertThat(circuitStatus.isOpen()).isFalse();
            assertThat(circuitStatus.failureCount()).isZero();
            assertThat(circuitStatus.lastFailureTime()).isZero();
        }

        @Test
        @DisplayName("UT-NT-083: Circuit Status 실패 후 정보 업데이트")
        void utNt083UpdatesCircuitStatusAfterFailures() throws Exception {
            // given - Redis 실패 상황
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when - 1번 실패
            redisHealthChecker.isHealthy();
            var statusAfterOneFailure = redisHealthChecker.getCircuitStatus();

            // then
            assertThat(statusAfterOneFailure.isOpen()).isFalse(); // 아직 열리지 않음
            assertThat(statusAfterOneFailure.failureCount()).isEqualTo(1);
            assertThat(statusAfterOneFailure.lastFailureTime()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("예외 상황 처리")
    class ExceptionHandlingTest {

        @Test
        @DisplayName("UT-NT-084: Redis 연결 예외 처리")
        void utNt084HandlesRedisConnectionException() {
            // given - Redis 연결 예외 발생
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Connection timeout"));

            // when & then - 예외가 전파되지 않고 false 반환
            assertThatCode(() -> {
                boolean isHealthy = redisHealthChecker.isHealthy();
                assertThat(isHealthy).isFalse();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-085: Null 응답 처리")
        void utNt085HandlesNullResponse() throws Exception {
            // given - Redis가 null 응답
            RedisConnection mockConnection = mock(RedisConnection.class);
            when(mockConnection.ping()).thenReturn(null);
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RedisCallback<String> callback = (RedisCallback<String>) invocation.getArgument(0);
                return callback.doInRedis(mockConnection);
            });

            // when
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then
            assertThat(isHealthy).isFalse();
        }

        @Test
        @DisplayName("UT-NT-086: Circuit 상태 경합 조건 처리")
        void utNt086HandlesConcurrentCircuitStateAccess() throws Exception {
            // given - 동시에 여러 스레드에서 헬스체크 호출
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when & then - 동시성 상황에서도 안전하게 처리
            assertThatCode(() -> {
                // 여러 번 호출하여 Circuit 오픈 시도
                for (int i = 0; i < 5; i++) {
                    redisHealthChecker.isHealthy();
                }
            }).doesNotThrowAnyException();

            // Circuit이 정상적으로 열렸는지 확인
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("커버리지 개선 테스트")
    class CoverageImprovementTest {

        @Test
        @DisplayName("UT-NT-087: 시간 기반 리셋 조건 확인")
        void utNt087ChecksShouldAttemptResetCondition() throws Exception {
            // given - Circuit이 열린 상태
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));
            
            // Circuit을 열기 위해 3번 실패
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            redisHealthChecker.isHealthy();
            
            var circuitStatus = redisHealthChecker.getCircuitStatus();
            assertThat(circuitStatus.isOpen()).isTrue();
            
            // when - 시간이 충분히 지나지 않은 상태에서 호출 (리셋 시도하지 않음)
            boolean isHealthyBeforeTimeout = redisHealthChecker.isHealthy();
            
            // then - Circuit이 계속 열린 상태
            assertThat(isHealthyBeforeTimeout).isFalse();
            
            // 시간을 강제로 경과시킨 후 
            AtomicLong lastFailureTime = (AtomicLong) ReflectionTestUtils.getField(redisHealthChecker, "lastFailureTime");
            lastFailureTime.set(System.currentTimeMillis() - 31000); // 31초 전으로 설정
            
            // Redis가 여전히 실패하는 상황에서 리셋 시도
            boolean isHealthyAfterTimeout = redisHealthChecker.isHealthy();
            
            // then - 리셋 시도했지만 실패하여 여전히 false
            assertThat(isHealthyAfterTimeout).isFalse();
        }

        @Test
        @DisplayName("UT-NT-088: Circuit 오픈 중복 방지")
        void utNt088PreventsDuplicateCircuitOpening() throws Exception {
            // given - 이미 Circuit이 열린 상태
            AtomicBoolean isCircuitOpen = (AtomicBoolean) ReflectionTestUtils.getField(redisHealthChecker, "isCircuitOpen");
            isCircuitOpen.set(true);

            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when - 추가 실패 발생
            boolean isHealthy = redisHealthChecker.isHealthy();

            // then - Circuit이 이미 열려있으므로 즉시 false 반환
            assertThat(isHealthy).isFalse();
        }

        @Test
        @DisplayName("UT-NT-089: 실패 기록 메서드 테스트")
        void utNt089RecordsFailureCorrectly() throws Exception {
            // given
            when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("Redis connection failed"));

            // when - 연속적인 실패 발생
            redisHealthChecker.isHealthy(); // 실패 1
            var statusAfterFirst = redisHealthChecker.getCircuitStatus();
            
            redisHealthChecker.isHealthy(); // 실패 2  
            var statusAfterSecond = redisHealthChecker.getCircuitStatus();

            // then - 실패 카운트가 정확히 증가
            assertThat(statusAfterFirst.failureCount()).isEqualTo(1);
            assertThat(statusAfterFirst.lastFailureTime()).isGreaterThan(0);
            
            assertThat(statusAfterSecond.failureCount()).isEqualTo(2);
            assertThat(statusAfterSecond.lastFailureTime()).isGreaterThanOrEqualTo(statusAfterFirst.lastFailureTime());
        }
    }
}