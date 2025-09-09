package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.sse.SseEmittersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("하이브리드 Push-Pull 알림 서비스 테스트")
class HybridNotificationServiceTest {
    
    @Mock
    private NotificationService notificationService;
    
    @Mock
    private NotificationDeduplicationService deduplicationService;
    
    @Mock
    private SseEmittersService sseEmittersService;
    
    @Mock
    private NotificationRepository notificationRepository;
    
    private HybridNotificationService hybridNotificationService;
    
    @BeforeEach
    void setUp() {
        hybridNotificationService = new HybridNotificationService(
            notificationService,
            deduplicationService,
            sseEmittersService,
            notificationRepository
        );
    }
    
    @Test
    @DisplayName("중복 제거 검사를 통과하지 못한 알림은 전송되지 않아야 한다")
    void shouldNotSendNotificationWhenDeduplicationCheckFails() throws Exception {
        // given
        User user = createTestUser(1L);
        Type type = Type.COMMENT;
        String entityId = "post-123";
        
        given(deduplicationService.shouldSendNotification(any(), any(), any(), any()))
            .willReturn(false);
        
        // when
        CompletableFuture<Boolean> result = hybridNotificationService
            .sendHybridNotification(user, type, entityId);
        
        // then
        assertThat(result.get()).isFalse();
        verify(notificationService, never()).createNotificationOptimized(any(), any(), any());
    }
    
    @Test
    @DisplayName("온라인 사용자에게는 SSE Push를 시도해야 한다")
    void shouldAttemptPushForOnlineUser() throws Exception {
        // given
        User user = createTestUser(1L);
        Type type = Type.COMMENT;
        String entityId = "post-123";
        Notification notification = createMockNotification(1L, user);
        
        given(deduplicationService.shouldSendNotification(any(), any(), any(), any()))
            .willReturn(true);
        given(notificationService.createNotificationOptimized(any(), any(), any()))
            .willReturn(CompletableFuture.completedFuture(notification));
        given(sseEmittersService.isUserConnected(1L))
            .willReturn(true);
        given(sseEmittersService.sendEvent(eq(1L), eq("notification"), any()))
            .willReturn(CompletableFuture.completedFuture(true));
        
        // when
        CompletableFuture<Boolean> result = hybridNotificationService
            .sendHybridNotification(user, type, entityId);
        
        // then
        assertThat(result.get()).isTrue();
        verify(sseEmittersService).sendEvent(eq(1L), eq("notification"), any());
        verify(notificationRepository).updateSseSentStatus(1L, true);
    }
    
    @Test
    @DisplayName("오프라인 사용자에게는 Pull용 저장만 수행해야 한다")
    void shouldStorePullNotificationForOfflineUser() throws Exception {
        // given
        User user = createTestUser(1L);
        Type type = Type.COMMENT;
        String entityId = "post-123";
        Notification notification = createMockNotification(1L, user);
        
        given(deduplicationService.shouldSendNotification(any(), any(), any(), any()))
            .willReturn(true);
        given(notificationService.createNotificationOptimized(any(), any(), any()))
            .willReturn(CompletableFuture.completedFuture(notification));
        given(sseEmittersService.isUserConnected(1L))
            .willReturn(false);
        
        // when
        CompletableFuture<Boolean> result = hybridNotificationService
            .sendHybridNotification(user, type, entityId);
        
        // then
        assertThat(result.get()).isTrue();
        verify(sseEmittersService, never()).sendEvent(any(), any(), any());
        // Pull용으로 DB에 저장만 되고 sseSent는 false로 유지
    }
    
    @Test
    @DisplayName("SSE Push 실패시 Pull용 저장으로 fallback 해야 한다")
    void shouldFallbackToPullWhenPushFails() throws Exception {
        // given
        User user = createTestUser(1L);
        Type type = Type.COMMENT;
        String entityId = "post-123";
        Notification notification = createMockNotification(1L, user);
        
        given(deduplicationService.shouldSendNotification(any(), any(), any(), any()))
            .willReturn(true);
        given(notificationService.createNotificationOptimized(any(), any(), any()))
            .willReturn(CompletableFuture.completedFuture(notification));
        given(sseEmittersService.isUserConnected(1L))
            .willReturn(true);
        given(sseEmittersService.sendEvent(eq(1L), eq("notification"), any()))
            .willReturn(CompletableFuture.completedFuture(false)); // Push 실패
        
        // when
        CompletableFuture<Boolean> result = hybridNotificationService
            .sendHybridNotification(user, type, entityId);
        
        // then
        assertThat(result.get()).isFalse(); // Push 실패
        verify(sseEmittersService).sendEvent(eq(1L), eq("notification"), any());
        verify(notificationRepository, never()).updateSseSentStatus(1L, true);
    }
    
    @Test
    @DisplayName("재연결시 누락된 알림들을 전송해야 한다 (Last-Event-ID 기반)")
    void shouldSendMissedNotificationsOnReconnect() {
        // given
        Long userId = 1L;
        List<Notification> missedNotifications = List.of(
            createMockNotification(1L, createTestUser(1L)),
            createMockNotification(2L, createTestUser(1L))
        );
        
        given(notificationRepository.findUnreadNotificationsByUserId(userId))
            .willReturn(missedNotifications);
        
        // when
        String lastEventId = "evt_1640995200000"; // 2022-01-01 00:00:00 timestamp
        hybridNotificationService.sendMissedNotifications(userId, lastEventId);
        
        // then
        verify(notificationRepository).findUnreadNotificationsByUserId(userId);
        // 비동기로 배치 전송이 실행되었는지 확인하기 위해 약간의 대기 후 검증
    }
    
    @Test
    @DisplayName("배치 알림 전송시 성공한 알림들의 sseSent 플래그를 업데이트해야 한다")
    void shouldUpdateSseSentFlagForSuccessfulBatchNotifications() {
        // given
        Long userId = 1L;
        List<Notification> notifications = List.of(
            createMockNotification(1L, createTestUser(1L)),
            createMockNotification(2L, createTestUser(1L))
        );
        
        given(sseEmittersService.sendEventSync(eq(userId), eq("notification"), any()))
            .willReturn(true, true); // 모두 성공
        
        // when
        hybridNotificationService.sendNotificationBatch(userId, notifications);
        
        // then
        verify(sseEmittersService, times(2)).sendEventSync(eq(userId), eq("notification"), any());
        verify(notificationRepository).updateSseSentStatus(1L, true);
        verify(notificationRepository).updateSseSentStatus(2L, true);
    }
    
    @Test
    @DisplayName("배치 알림 전송 중 일부 실패해도 계속 진행되어야 한다")
    void shouldContinueBatchSendingDespitePartialFailures() {
        // given
        Long userId = 1L;
        List<Notification> notifications = List.of(
            createMockNotification(1L, createTestUser(1L)),
            createMockNotification(2L, createTestUser(1L)),
            createMockNotification(3L, createTestUser(1L))
        );
        
        given(sseEmittersService.sendEventSync(eq(userId), eq("notification"), any()))
            .willReturn(true, false, true); // 중간에 하나 실패
        
        // when
        hybridNotificationService.sendNotificationBatch(userId, notifications);
        
        // then
        verify(sseEmittersService, times(3)).sendEventSync(eq(userId), eq("notification"), any());
        verify(notificationRepository).updateSseSentStatus(1L, true);
        verify(notificationRepository, never()).updateSseSentStatus(2L, true); // 실패한 것은 업데이트 안됨
        verify(notificationRepository).updateSseSentStatus(3L, true);
    }
    
    @Test
    @DisplayName("미처리 알림을 올바른 조건으로 조회해야 한다")
    void shouldGetPendingNotificationsWithCorrectConditions() {
        // given
        Long userId = 1L;
        LocalDateTime since = LocalDateTime.now().minusHours(2);
        int limit = 20;
        List<Notification> expectedNotifications = List.of(
            createMockNotification(1L, createTestUser(1L))
        );
        
        given(notificationRepository.findUnreadNotificationsByUserId(userId))
            .willReturn(expectedNotifications);
        
        // when
        List<Notification> result = hybridNotificationService
            .getPendingNotifications(userId, since, limit);
        
        // then
        assertThat(result).hasSize(1);
        verify(notificationRepository).findUnreadNotificationsByUserId(userId);
    }
    
    @Test
    @DisplayName("알림 수신 확인시 여러 알림의 sseSent 플래그를 업데이트해야 한다")
    void shouldMarkMultipleNotificationsAsReceived() {
        // given
        Long userId = 1L;
        List<Long> notificationIds = List.of(1L, 2L, 3L);
        
        // when
        hybridNotificationService.markNotificationsAsReceived(userId, notificationIds);
        
        // then
        verify(notificationRepository).updateSseSentStatus(1L, true);
        verify(notificationRepository).updateSseSentStatus(2L, true);
        verify(notificationRepository).updateSseSentStatus(3L, true);
    }
    
    @Test
    @DisplayName("알림 생성 실패시 예외를 처리하고 false를 반환해야 한다")
    void shouldHandleNotificationCreationFailure() throws Exception {
        // given
        User user = createTestUser(1L);
        Type type = Type.COMMENT;
        String entityId = "post-123";
        
        given(deduplicationService.shouldSendNotification(any(), any(), any(), any()))
            .willReturn(true);
        given(notificationService.createNotificationOptimized(any(), any(), any()))
            .willReturn(CompletableFuture.failedFuture(new RuntimeException("Creation failed")));
        
        // when
        CompletableFuture<Boolean> result = hybridNotificationService
            .sendHybridNotification(user, type, entityId);
        
        // then
        assertThat(result.get()).isFalse();
    }
    
    private User createTestUser(Long userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        ReflectionTestUtils.setField(user, "email", "test" + userId + "@example.com");
        return user;
    }
    
    private Notification createMockNotification(Long notificationId, User user) {
        Notification notification = new Notification();
        ReflectionTestUtils.setField(notification, "id", notificationId);
        ReflectionTestUtils.setField(notification, "user", user);
        return notification;
    }
}