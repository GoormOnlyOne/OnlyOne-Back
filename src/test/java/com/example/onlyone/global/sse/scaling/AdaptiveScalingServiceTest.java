package com.example.onlyone.global.sse.scaling;

import com.example.onlyone.global.sse.monitoring.SsePerformanceMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("적응형 스케일링 서비스 테스트")
class AdaptiveScalingServiceTest {
    
    @Mock
    private SsePerformanceMonitor performanceMonitor;
    
    private AdaptiveScalingService scalingService;
    
    @BeforeEach
    void setUp() {
        scalingService = new AdaptiveScalingService(performanceMonitor);
        // 테스트용 설정값 주입
        ReflectionTestUtils.setField(scalingService, "scalingEnabled", true);
        ReflectionTestUtils.setField(scalingService, "minBatchSize", 100);
        ReflectionTestUtils.setField(scalingService, "maxBatchSize", 2000);
        ReflectionTestUtils.setField(scalingService, "minThreadPoolSize", 4);
        ReflectionTestUtils.setField(scalingService, "maxThreadPoolSize", 20);
    }
    
    @Test
    @DisplayName("좋은 성능일 때 배치 크기를 증가시켜야 한다")
    void shouldIncreaseBatchSizeWhenPerformanceIsGood() {
        // given
        int initialBatchSize = getCurrentBatchSize();
        SsePerformanceMonitor.PerformanceSnapshot goodPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 10, 0.01, 200.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(goodPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        assertThat(newBatchSize).isGreaterThan(initialBatchSize);
    }
    
    @Test
    @DisplayName("나쁜 성능일 때 배치 크기를 감소시켜야 한다")
    void shouldDecreaseBatchSizeWhenPerformanceIsBad() {
        // given
        int initialBatchSize = getCurrentBatchSize();
        SsePerformanceMonitor.PerformanceSnapshot badPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 100, 0.1, 2000.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(badPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        assertThat(newBatchSize).isLessThan(initialBatchSize);
    }
    
    @Test
    @DisplayName("배치 크기가 최소값 아래로 떨어지지 않아야 한다")
    void shouldNotDecreaseBatchSizeBelowMinimum() {
        // given
        setCurrentBatchSize(120); // 최소값(100)에 가까운 값으로 설정
        SsePerformanceMonitor.PerformanceSnapshot badPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 200, 0.2, 3000.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(badPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        assertThat(newBatchSize).isGreaterThanOrEqualTo(100); // 최소값 보장
    }
    
    @Test
    @DisplayName("배치 크기가 최대값을 초과하지 않아야 한다")
    void shouldNotIncreaseBatchSizeAboveMaximum() {
        // given
        setCurrentBatchSize(1950); // 최대값(2000)에 가까운 값으로 설정
        SsePerformanceMonitor.PerformanceSnapshot excellentPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(10000, 1, 0.0001, 50.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(excellentPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        assertThat(newBatchSize).isLessThanOrEqualTo(2000); // 최대값 보장
    }
    
    @Test
    @DisplayName("좋은 성능일 때 레이트 리미트를 증가시켜야 한다")
    void shouldIncreaseRateLimitWhenPerformanceIsGood() {
        // given
        int initialRateLimit = getCurrentRateLimit();
        SsePerformanceMonitor.PerformanceSnapshot excellentPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(5000, 5, 0.001, 100.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(excellentPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newRateLimit = getCurrentRateLimit();
        assertThat(newRateLimit).isGreaterThan(initialRateLimit);
    }
    
    @Test
    @DisplayName("시스템 부하가 높을 때 레이트 리미트를 감소시켜야 한다")
    void shouldDecreaseRateLimitWhenSystemIsUnderLoad() {
        // given
        int initialRateLimit = getCurrentRateLimit();
        SsePerformanceMonitor.PerformanceSnapshot loadedPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 50, 0.05, 1500.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(loadedPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newRateLimit = getCurrentRateLimit();
        assertThat(newRateLimit).isLessThan(initialRateLimit);
    }
    
    @Test
    @DisplayName("시스템 보호 모드를 올바르게 감지해야 한다")
    void shouldDetectSystemProtectionMode() {
        // given - 높은 실패율과 지연시간으로 성능 히스토리 설정
        SsePerformanceMonitor.PerformanceSnapshot criticalPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 150, 0.15, 2500.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(criticalPerformance);
        
        // 여러 번 호출하여 히스토리 구축
        for (int i = 0; i < 5; i++) {
            scalingService.adjustSystemParameters();
        }
        
        // when
        boolean isInProtectionMode = scalingService.isSystemInProtectionMode();
        
        // then
        assertThat(isInProtectionMode).isTrue();
    }
    
    @Test
    @DisplayName("현재 적응형 설정값을 올바르게 반환해야 한다")
    void shouldReturnCurrentAdaptiveSettings() {
        // when
        AdaptiveScalingService.AdaptiveSettings settings = scalingService.getCurrentSettings();
        
        // then
        assertThat(settings).isNotNull();
        assertThat(settings.batchSize()).isPositive();
        assertThat(settings.rateLimit()).isPositive();
        assertThat(settings.threadPoolSize()).isPositive();
    }
    
    @Test
    @DisplayName("긴급 스케일 다운이 올바르게 작동해야 한다")
    void shouldPerformEmergencyScaleDown() {
        // given
        int initialBatchSize = getCurrentBatchSize();
        int initialRateLimit = getCurrentRateLimit();
        
        // when
        scalingService.emergencyScaleDown();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        int newRateLimit = getCurrentRateLimit();
        
        assertThat(newBatchSize).isEqualTo(100); // 최소값으로 설정
        assertThat(newRateLimit).isEqualTo(5000); // 긴급 최소값으로 설정
        assertThat(newBatchSize).isLessThanOrEqualTo(initialBatchSize);
        assertThat(newRateLimit).isLessThanOrEqualTo(initialRateLimit);
    }
    
    @Test
    @DisplayName("긴급 스케일 업이 올바르게 작동해야 한다")
    void shouldPerformEmergencyScaleUp() {
        // given
        int initialBatchSize = getCurrentBatchSize();
        int initialRateLimit = getCurrentRateLimit();
        
        // when
        scalingService.emergencyScaleUp();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        int newRateLimit = getCurrentRateLimit();
        
        assertThat(newBatchSize).isEqualTo(2000); // 최대값으로 설정
        assertThat(newRateLimit).isEqualTo(50000); // 긴급 최대값으로 설정
        assertThat(newBatchSize).isGreaterThanOrEqualTo(initialBatchSize);
        assertThat(newRateLimit).isGreaterThanOrEqualTo(initialRateLimit);
    }
    
    @Test
    @DisplayName("스케일링이 비활성화되었을 때 조정하지 않아야 한다")
    void shouldNotAdjustWhenScalingIsDisabled() {
        // given
        ReflectionTestUtils.setField(scalingService, "scalingEnabled", false);
        int initialBatchSize = getCurrentBatchSize();
        int initialRateLimit = getCurrentRateLimit();
        
        SsePerformanceMonitor.PerformanceSnapshot anyPerformance = 
            new SsePerformanceMonitor.PerformanceSnapshot(1000, 100, 0.1, 2000.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(anyPerformance);
        
        // when
        scalingService.adjustSystemParameters();
        
        // then
        int newBatchSize = getCurrentBatchSize();
        int newRateLimit = getCurrentRateLimit();
        
        assertThat(newBatchSize).isEqualTo(initialBatchSize);
        assertThat(newRateLimit).isEqualTo(initialRateLimit);
    }
    
    @Test
    @DisplayName("성능 히스토리가 없을 때 안전하게 처리해야 한다")
    void shouldHandleEmptyPerformanceHistorySafely() {
        // given - 새로운 인스턴스 (히스토리 없음)
        AdaptiveScalingService newScalingService = new AdaptiveScalingService(performanceMonitor);
        ReflectionTestUtils.setField(newScalingService, "scalingEnabled", true);
        
        SsePerformanceMonitor.PerformanceSnapshot performance = 
            new SsePerformanceMonitor.PerformanceSnapshot(100, 5, 0.05, 500.0, LocalDateTime.now());
        
        given(performanceMonitor.getCurrentPerformance())
            .willReturn(performance);
        
        // when & then - 예외 없이 실행되어야 함
        newScalingService.adjustSystemParameters();
        
        AdaptiveScalingService.AdaptiveSettings settings = newScalingService.getCurrentSettings();
        assertThat(settings).isNotNull();
    }
    
    private int getCurrentBatchSize() {
        AtomicInteger batchSize = (AtomicInteger) ReflectionTestUtils.getField(scalingService, "currentBatchSize");
        return batchSize.get();
    }
    
    private void setCurrentBatchSize(int size) {
        AtomicInteger batchSize = (AtomicInteger) ReflectionTestUtils.getField(scalingService, "currentBatchSize");
        batchSize.set(size);
    }
    
    private int getCurrentRateLimit() {
        AtomicInteger rateLimit = (AtomicInteger) ReflectionTestUtils.getField(scalingService, "currentRateLimit");
        return rateLimit.get();
    }
}