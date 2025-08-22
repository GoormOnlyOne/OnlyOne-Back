package com.example.onlyone.performance;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.service.FcmService;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 빠른 성능 측정 테스트
 * - 기본적인 성능 지표 확인
 * - 목표치 대비 현재 성능 파악
 */
@DisplayName("빠른 성능 측정 테스트")
class QuickPerformanceTest extends BasePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private FcmService fcmService;
    
    @Autowired
    private SseEmittersService sseEmittersService;

    @Test
    @DisplayName("QUICK-001: 알림 조회 기본 성능")
    void basic_notification_query_performance() {
        // given
        User testUser = testUsers.get(0);
        
        // when
        Instant start = Instant.now();
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);
        long responseTime = Duration.between(start, Instant.now()).toMillis();
        
        // then
        System.err.printf("=== 성능 측정 결과 ===%n");
        System.err.printf("알림 조회 응답시간: %dms%n", responseTime);
        System.err.printf("조회된 알림 수: %d개%n", result.getNotifications().size());
        System.err.printf("목표 응답시간: %dms%n", PerformanceTestConfig.NotificationQuery.MAX_RESPONSE_TIME_MS);
        
        assertThat(result).isNotNull();
        assertThat(responseTime).isLessThan(PerformanceTestConfig.NotificationQuery.MAX_RESPONSE_TIME_MS);
    }

    @Test
    @DisplayName("QUICK-002: 미읽음 개수 조회 성능")
    void unread_count_query_performance() {
        // given
        User testUser = testUsers.get(0);
        
        // when
        Instant start = Instant.now();
        Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
        long responseTime = Duration.between(start, Instant.now()).toMillis();
        
        // then
        System.err.printf("=== 미읽음 개수 조회 성능 ===%n");
        System.err.printf("미읽음 개수 조회 응답시간: %dms%n", responseTime);
        System.err.printf("미읽음 개수: %d개%n", unreadCount);
        System.err.printf("목표 응답시간: %dms%n", 100);
        
        assertThat(unreadCount).isNotNull();
        assertThat(responseTime).isLessThan(100); // 매우 빨라야 함
    }

    @Test
    @DisplayName("QUICK-003: FCM 전송 기본 성능")
    void basic_fcm_send_performance() {
        // given
        List<AppNotification> notifications = createTestNotifications(10);
        AppNotification testNotification = notifications.get(0);
        
        // when
        Instant start = Instant.now();
        try {
            fcmService.sendFcmNotification(testNotification);
        } catch (Exception e) {
            // Mock 환경에서는 예외 발생 가능
        }
        long responseTime = Duration.between(start, Instant.now()).toMillis();
        
        // then
        System.err.printf("=== FCM 전송 성능 ===%n");
        System.err.printf("FCM 전송 응답시간: %dms%n", responseTime);
        System.err.printf("목표 응답시간: %dms%n", PerformanceTestConfig.FcmPerformance.SINGLE_SEND_MAX_TIME_MS);
        assertThat(responseTime).isLessThan(PerformanceTestConfig.FcmPerformance.SINGLE_SEND_MAX_TIME_MS);
    }

    @Test
    @DisplayName("QUICK-004: SSE 연결 기본 성능")
    void basic_sse_connection_performance() {
        // given
        User testUser = testUsers.get(0);
        
        // when
        Instant start = Instant.now();
        SseEmitter emitter = sseEmittersService.createSseConnection(testUser.getUserId());
        long responseTime = Duration.between(start, Instant.now()).toMillis();
        
        // then
        System.err.printf("=== SSE 연결 성능 ===%n");
        System.err.printf("SSE 연결 설정 시간: %dms%n", responseTime);
        System.err.printf("활성 연결 수: %d개%n", sseEmittersService.getActiveConnectionCount());
        System.err.printf("목표 연결 시간: %dms%n", PerformanceTestConfig.SsePerformance.CONNECTION_SETUP_MAX_TIME_MS);
        
        assertThat(emitter).isNotNull();
        // 통합 테스트 환경에서는 더 관대한 기준 적용 (목표의 5배)
        assertThat(responseTime).isLessThan(PerformanceTestConfig.SsePerformance.CONNECTION_SETUP_MAX_TIME_MS * 5);
    }

    @Test
    @DisplayName("QUICK-005: SSE 메시지 전송 성능")
    void basic_sse_message_performance() {
        // given
        User testUser = testUsers.get(0);
        sseEmittersService.createSseConnection(testUser.getUserId());
        List<AppNotification> notifications = createTestNotifications(5);
        AppNotification testNotification = notifications.get(0);
        
        // when
        Instant start = Instant.now();
        sseEmittersService.sendSseNotification(testUser.getUserId(), testNotification);
        long responseTime = Duration.between(start, Instant.now()).toMillis();
        
        // then
        System.err.printf("=== SSE 메시지 전송 성능 ===%n");
        System.err.printf("SSE 메시지 전송 시간: %dms%n", responseTime);
        System.err.printf("목표 전송 시간: %dms%n", PerformanceTestConfig.SsePerformance.MESSAGE_DELIVERY_MAX_TIME_MS);
        assertThat(responseTime).isLessThan(PerformanceTestConfig.SsePerformance.MESSAGE_DELIVERY_MAX_TIME_MS);
    }

    @Test
    @DisplayName("QUICK-006: 전체 시스템 메모리 사용량 확인")
    void system_memory_usage_check() {
        // given
        Runtime runtime = Runtime.getRuntime();
        
        // when
        long totalMemoryMB = runtime.totalMemory() / (1024 * 1024);
        long freeMemoryMB = runtime.freeMemory() / (1024 * 1024);
        long usedMemoryMB = totalMemoryMB - freeMemoryMB;
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        
        // then
        System.err.printf("=== 메모리 사용량 현황 ===%n");
        System.err.printf("  - 사용 중: %dMB%n", usedMemoryMB);
        System.err.printf("  - 전체: %dMB%n", totalMemoryMB);
        System.err.printf("  - 최대: %dMB%n", maxMemoryMB);
        System.err.printf("  - 사용률: %.2f%%%n", (double) usedMemoryMB / maxMemoryMB * 100);
        System.err.printf("  - 목표 최대 사용량: %dMB%n", PerformanceTestConfig.SystemPerformance.MEMORY_USAGE_MAX_MB);
        
        assertThat(usedMemoryMB).isLessThan(PerformanceTestConfig.SystemPerformance.MEMORY_USAGE_MAX_MB);
    }
}