package com.example.onlyone.global.sse.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSE 성능 모니터링 테스트")
class SsePerformanceMonitorTest {
    
    private SsePerformanceMonitor performanceMonitor;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    
    @BeforeEach
    void setUp() {
        performanceMonitor = new SsePerformanceMonitor();
        
        // 로그 캡처 설정
        logger = (Logger) LoggerFactory.getLogger(SsePerformanceMonitor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }
    
    @Test
    @DisplayName("알림 전송 성공 이벤트를 기록해야 한다")
    void shouldRecordNotificationSentEvent() {
        // given
        Long userId = 1L;
        String notificationType = "COMMENT";
        long latency = 150L;
        
        // when
        performanceMonitor.recordNotificationSent(userId, notificationType, latency);
        
        // then
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        assertThat(snapshot.totalSent()).isEqualTo(1L);
        assertThat(snapshot.averageLatencyMs()).isEqualTo(150.0);
    }
    
    @Test
    @DisplayName("알림 전송 실패 이벤트를 기록해야 한다")
    void shouldRecordNotificationFailedEvent() {
        // given
        Long userId = 1L;
        String notificationType = "COMMENT";
        String reason = "Connection closed";
        
        // when
        performanceMonitor.recordNotificationFailed(userId, notificationType, reason);
        
        // then
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        assertThat(snapshot.totalFailed()).isEqualTo(1L);
        assertThat(snapshot.failureRate()).isEqualTo(1.0); // 100% 실패율
    }
    
    @Test
    @DisplayName("SSE 연결 성공 이벤트를 기록해야 한다")
    void shouldRecordConnectionEstablishedEvent() {
        // given
        Long userId = 1L;
        
        // when
        performanceMonitor.recordConnectionEstablished(userId);
        
        // then
        List<ILoggingEvent> logEvents = logAppender.list;
        assertThat(logEvents).hasSize(1);
        assertThat(logEvents.get(0).getFormattedMessage())
            .contains("SSE connection established")
            .contains("userId=1");
    }
    
    @Test
    @DisplayName("SSE 연결 종료 이벤트를 기록해야 한다")
    void shouldRecordConnectionClosedEvent() {
        // given
        Long userId = 1L;
        String reason = "Client disconnected";
        
        // when
        performanceMonitor.recordConnectionClosed(userId, reason);
        
        // then
        List<ILoggingEvent> logEvents = logAppender.list;
        assertThat(logEvents).hasSize(1);
        assertThat(logEvents.get(0).getFormattedMessage())
            .contains("SSE connection closed")
            .contains("userId=1")
            .contains("reason=Client disconnected");
    }
    
    @Test
    @DisplayName("실패율을 정확히 계산해야 한다")
    void shouldCalculateFailureRateCorrectly() {
        // given
        performanceMonitor.recordNotificationSent(1L, "COMMENT", 100L);
        performanceMonitor.recordNotificationSent(2L, "LIKE", 150L);
        performanceMonitor.recordNotificationFailed(3L, "COMMENT", "Error");
        
        // when
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        
        // then
        assertThat(snapshot.totalSent()).isEqualTo(2L);
        assertThat(snapshot.totalFailed()).isEqualTo(1L);
        assertThat(snapshot.failureRate()).isEqualTo(1.0 / 3.0); // 33.33%
    }
    
    @Test
    @DisplayName("평균 지연시간을 정확히 계산해야 한다")
    void shouldCalculateAverageLatencyCorrectly() {
        // given
        performanceMonitor.recordNotificationSent(1L, "COMMENT", 100L);
        performanceMonitor.recordNotificationSent(2L, "LIKE", 200L);
        performanceMonitor.recordNotificationSent(3L, "FOLLOW", 300L);
        
        // when
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        
        // then
        assertThat(snapshot.averageLatencyMs()).isEqualTo(200.0); // (100 + 200 + 300) / 3
    }
    
    @Test
    @DisplayName("현재 성능 스냅샷을 올바르게 생성해야 한다")
    void shouldCreateCorrectPerformanceSnapshot() {
        // given
        performanceMonitor.recordNotificationSent(1L, "COMMENT", 150L);
        performanceMonitor.recordNotificationFailed(2L, "LIKE", "Error");
        
        // when
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        
        // then
        assertThat(snapshot.totalSent()).isEqualTo(1L);
        assertThat(snapshot.totalFailed()).isEqualTo(1L);
        assertThat(snapshot.failureRate()).isEqualTo(0.5);
        assertThat(snapshot.averageLatencyMs()).isEqualTo(150.0);
        assertThat(snapshot.timestamp()).isBefore(LocalDateTime.now().plusSeconds(1));
    }
    
    @Test
    @DisplayName("메트릭스 수집 및 리포트가 정상 작동해야 한다")
    void shouldCollectAndReportMetrics() {
        // given
        performanceMonitor.recordNotificationSent(1L, "COMMENT", 100L);
        performanceMonitor.recordNotificationSent(2L, "LIKE", 200L);
        performanceMonitor.recordNotificationFailed(3L, "COMMENT", "Error");
        performanceMonitor.recordConnectionEstablished(1L);
        performanceMonitor.recordConnectionClosed(2L, "Timeout");
        
        // when
        performanceMonitor.collectAndReportMetrics();
        
        // then
        List<ILoggingEvent> logEvents = logAppender.list;
        
        // 메트릭스 리포트 로그 확인
        boolean metricsLogFound = logEvents.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("SSE Metrics [10s]"));
        
        assertThat(metricsLogFound).isTrue();
        
        // 메트릭스 리셋 확인 (다음 수집에서는 0이어야 함)
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        assertThat(snapshot.totalSent()).isEqualTo(0L);
        assertThat(snapshot.totalFailed()).isEqualTo(0L);
    }
    
    @Test
    @DisplayName("높은 실패율에 대한 알림을 발생시켜야 한다")
    void shouldTriggerHighFailureRateAlert() {
        // given - 높은 실패율 상황 시뮬레이션
        for (int i = 0; i < 10; i++) {
            performanceMonitor.recordNotificationSent((long) i, "COMMENT", 100L);
        }
        for (int i = 0; i < 150; i++) { // 높은 실패율 생성
            performanceMonitor.recordNotificationFailed((long) i, "COMMENT", "Error");
        }
        
        // when
        performanceMonitor.collectAndReportMetrics();
        
        // then
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean alertLogFound = logEvents.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("HIGH FAILURE RATE ALERT"));
        
        assertThat(alertLogFound).isTrue();
    }
    
    @Test
    @DisplayName("높은 지연시간에 대한 알림을 발생시켜야 한다")
    void shouldTriggerHighLatencyAlert() {
        // given - 높은 지연시간 상황 시뮬레이션 (100개 이상의 연결로 임계값 달성)
        for (int i = 0; i < 150; i++) {
            performanceMonitor.recordNotificationSent((long) i, "COMMENT", 2000L); // 2초 지연
            performanceMonitor.recordConnectionEstablished((long) i);
        }
        
        // when
        performanceMonitor.collectAndReportMetrics();
        
        // then
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean alertLogFound = logEvents.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("HIGH LATENCY ALERT"));
        
        assertThat(alertLogFound).isTrue();
    }
    
    @Test
    @DisplayName("알림 없이도 정상적으로 메트릭스를 수집해야 한다")
    void shouldCollectMetricsWithoutAnyEvents() {
        // given - 이벤트 없음
        
        // when
        performanceMonitor.collectAndReportMetrics();
        
        // then
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        assertThat(snapshot.totalSent()).isEqualTo(0L);
        assertThat(snapshot.totalFailed()).isEqualTo(0L);
        assertThat(snapshot.failureRate()).isEqualTo(0.0);
        assertThat(snapshot.averageLatencyMs()).isEqualTo(0.0);
    }
}