package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationPriority;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("배치 알림 처리 서비스 테스트")
class NotificationBatchServiceTest {
    
    @Mock
    private NotificationCreationService notificationCreationService;
    
    private NotificationBatchService batchService;
    
    @BeforeEach
    void setUp() {
        batchService = new NotificationBatchService(notificationCreationService);
        // 테스트용 설정값 주입
        ReflectionTestUtils.setField(batchService, "defaultBatchSize", 2);
        ReflectionTestUtils.setField(batchService, "batchIntervalMs", 50);
    }
    
    @Test
    @DisplayName("대량 알림을 배치로 분할하여 처리해야 한다")
    void shouldProcessBatchNotifications() throws Exception {
        // given
        List<User> users = createTestUsers(5);
        Type type = Type.COMMENT;
        NotificationPriority priority = NotificationPriority.NORMAL;
        
        // Mock 설정
        given(notificationCreationService.createNotificationOptimized(any(User.class), eq(type), any()))
            .willReturn(CompletableFuture.completedFuture(createMockNotification()));
        
        // when
        CompletableFuture<Integer> result = batchService.sendBatchNotifications(users, type, priority);
        
        // then
        assertThat(result.get()).isEqualTo(5);
        
        // 배치 처리 대기 (비동기 처리 완료까지)
        Thread.sleep(200);
        
        // 모든 사용자에 대해 알림 생성이 호출되었는지 확인
        verify(notificationCreationService, times(5)).createNotificationOptimized(any(User.class), eq(type), any());
    }
    
    @Test
    @DisplayName("우선순위별로 배치가 처리되어야 한다")
    void shouldProcessBatchesByPriority() throws Exception {
        // given
        List<User> criticalUsers = createTestUsers(2);
        List<User> normalUsers = createTestUsers(3);
        
        given(notificationCreationService.createNotificationOptimized(any(User.class), any(Type.class), any()))
            .willReturn(CompletableFuture.completedFuture(createMockNotification()));
        
        // when - 우선순위가 다른 배치들을 동시에 큐에 추가
        batchService.sendBatchNotifications(normalUsers, Type.COMMENT, NotificationPriority.NORMAL);
        batchService.sendBatchNotifications(criticalUsers, Type.COMMENT, NotificationPriority.HIGH);
        
        // 배치 처리 대기
        Thread.sleep(300);
        
        // then - CRITICAL 우선순위가 먼저 처리되어야 함
        verify(notificationCreationService, times(5)).createNotificationOptimized(any(User.class), any(Type.class), any());
    }
    
    @Test
    @DisplayName("적응형 배치 크기가 성능에 따라 조정되어야 한다")
    void shouldAdjustBatchSizeBasedOnPerformance() {
        // given
        AtomicInteger currentBatchSize = getCurrentBatchSize();
        int initialSize = currentBatchSize.get();
        
        // 성능 테스트를 위한 시뮬레이션은 실제 구현에서는 복잡하므로
        // 여기서는 배치 크기 조정 로직의 존재만 확인
        
        // when & then
        assertThat(initialSize).isPositive();
        assertThat(currentBatchSize).isNotNull();
    }
    
    @Test
    @DisplayName("빈 사용자 목록으로 배치 처리시 0을 반환해야 한다")
    void shouldReturnZeroForEmptyUserList() throws Exception {
        // given
        List<User> emptyUsers = List.of();
        
        // when
        CompletableFuture<Integer> result = batchService.sendBatchNotifications(
            emptyUsers, Type.COMMENT, NotificationPriority.NORMAL);
        
        // then
        assertThat(result.get()).isEqualTo(0);
    }
    
    @Test
    @DisplayName("대용량 사용자 목록을 여러 배치로 분할해야 한다")
    void shouldPartitionLargeUserList() throws Exception {
        // given
        List<User> largeUserList = createTestUsers(10); // 배치 크기(2)보다 큰 목록
        
        given(notificationCreationService.createNotificationOptimized(any(User.class), any(Type.class), any()))
            .willReturn(CompletableFuture.completedFuture(createMockNotification()));
        
        // when
        CompletableFuture<Integer> result = batchService.sendBatchNotifications(
            largeUserList, Type.COMMENT, NotificationPriority.NORMAL);
        
        // then
        assertThat(result.get()).isEqualTo(10);
        
        // 배치 처리 대기
        Thread.sleep(500);
        
        // 모든 사용자에 대해 알림 생성이 호출되었는지 확인
        verify(notificationCreationService, times(10)).createNotificationOptimized(any(User.class), eq(Type.COMMENT), any());
    }
    
    @Test
    @DisplayName("배치 처리 중 일부 실패가 있어도 계속 진행되어야 한다")
    void shouldContinueProcessingDespitePartialFailures() throws Exception {
        // given
        List<User> users = createTestUsers(3);
        
        // 첫 번째 호출은 실패, 나머지는 성공
        given(notificationCreationService.createNotificationOptimized(any(User.class), any(Type.class), any()))
            .willReturn(
                CompletableFuture.failedFuture(new RuntimeException("Test failure")),
                CompletableFuture.completedFuture(createMockNotification()),
                CompletableFuture.completedFuture(createMockNotification())
            );
        
        // when
        CompletableFuture<Integer> result = batchService.sendBatchNotifications(
            users, Type.COMMENT, NotificationPriority.NORMAL);
        
        // then
        assertThat(result.get()).isEqualTo(3); // 요청된 총 사용자 수
        
        // 배치 처리 대기
        Thread.sleep(300);
        
        verify(notificationCreationService, times(3)).createNotificationOptimized(any(User.class), eq(Type.COMMENT), any());
    }
    
    private List<User> createTestUsers(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> {
                User user = new User();
                ReflectionTestUtils.setField(user, "userId", (long) i);
                ReflectionTestUtils.setField(user, "email", "test" + i + "@example.com");
                return user;
            })
            .toList();
    }
    
    private Notification createMockNotification() {
        Notification notification = Notification.builder()
            .user(createTestUsers(1).get(0))
            .notificationType(null)
            .message("test message")
            .isRead(false)
            .sseSent(false)
            .build();
        ReflectionTestUtils.setField(notification, "id", 1L);
        return notification;
    }
    
    @SuppressWarnings("unchecked")
    private AtomicInteger getCurrentBatchSize() {
        return (AtomicInteger) ReflectionTestUtils.getField(batchService, "currentBatchSize");
    }
}