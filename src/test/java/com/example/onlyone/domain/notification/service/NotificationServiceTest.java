package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 서비스 통합 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 서비스 테스트")
class NotificationServiceTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationService notificationService;

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        userRepository.deleteAll();

        // 테스트 사용자 생성
        testUser = User.builder()
                .kakaoId(12345L)
                .nickname("테스트사용자")
                .profileImage("test-profile.jpg")
                .status(Status.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);

        // 테스트 알림 타입 생성
        testNotificationType = NotificationType.of(Type.CHAT, "새로운 메시지가 도착했습니다: {0}");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
    }

    @Nested
    @DisplayName("알림 생성")
    class CreateNotification {
        
        @Test
        @DisplayName("동기 생성 성공")
        void sync_success() {
            // when
            Notification result = notificationService.createNotification(testUser, Type.CHAT, "테스트사용자", "안녕하세요").join();

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isNotNull();
            
            List<Notification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
            
            Notification saved = notifications.get(0);
            assertThat(saved.getUser().getUserId()).isEqualTo(testUser.getUserId());
            assertThat(saved.getNotificationType().getType()).isEqualTo(Type.CHAT);
            assertThat(saved.isRead()).isFalse();
        }
        
        @Test
        @DisplayName("비동기 생성 성공")
        void async_success() throws Exception {
            // when
            CompletableFuture<Notification> future = notificationService.createNotification(
                testUser, Type.CHAT, "비동기 테스트"
            );
            
            // then
            assertThat(future).isNotNull();
            Notification result = future.get(5, TimeUnit.SECONDS);
            assertThat(result).isNotNull();
            assertThat(result.getId()).isNotNull();
            
            // DB 확인 (비동기 처리 대기)
            Thread.sleep(100);
            List<Notification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
        }
        
        @Test
        @DisplayName("대량 알림 생성 성공")
        void bulk_success() throws Exception {
            // given
            User user2 = createAnotherUser();
            List<User> users = Arrays.asList(testUser, user2);
            
            // when
            List<CompletableFuture<Notification>> futures = notificationService.createBulkNotifications(
                users, Type.SETTLEMENT, "공지사항"
            );
            
            // then
            assertThat(futures).isNotNull();
            assertThat(futures).hasSize(2);
            
            // Wait for all to complete
            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allOf.get(5, TimeUnit.SECONDS);
            
            // DB 확인
            List<Notification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(2);
        }
        
        @Test
        @DisplayName("빈 사용자 목록으로 대량 생성")
        void bulk_emptyUsers() throws Exception {
            // given
            List<User> emptyUsers = List.of();
            
            // when
            List<CompletableFuture<Notification>> futures = notificationService.createBulkNotifications(
                emptyUsers, Type.SETTLEMENT, "공지사항"
            );
            
            // then
            assertThat(futures).isEmpty();
        }
    }

    @Nested
    @DisplayName("알림 조회")
    class GetNotifications {
        
        @Test
        @DisplayName("목록 조회 성공")
        void getList_success() {
            // given
            createTestNotifications(5);

            // when
            NotificationListResponseDto result = notificationService.getNotifications(
                    testUser.getUserId(), null, 10);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getNotifications()).hasSize(5);
            assertThat(result.getUnreadCount()).isEqualTo(5L);
            assertThat(result.isHasMore()).isFalse();
        }
        
        @Test
        @DisplayName("읽지 않은 알림 개수 조회 성공")
        void getUnreadCount_success() {
            // given
            createTestNotifications(3);

            // when
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());

            // then
            assertThat(unreadCount).isEqualTo(3L);
        }
        
        @Test
        @DisplayName("존재하지 않는 사용자 조회 시 예외")
        void getUnreadCount_userNotFound_throwsException() {
            // when & then
            assertThatThrownBy(() -> notificationService.getUnreadCount(999L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {
        
        @Test
        @DisplayName("단일 알림 읽음 처리 성공")
        void single_success() {
            // given
            Notification notification = createSingleNotification();

            // when
            notificationService.markAsRead(notification.getId(), testUser.getUserId());

            // then
            Notification updated = notificationRepository.findById(notification.getId()).orElse(null);
            assertThat(updated).isNotNull();
            assertThat(updated.isRead()).isTrue();
            assertThat(updated.getCreatedAt()).isNotNull();
        }
        
        @Test
        @DisplayName("모든 알림 읽음 처리 성공")
        void all_success() {
            // given
            createTestNotifications(5);

            // when
            notificationService.markAllAsRead(testUser.getUserId());

            // then
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(unreadCount).isZero();
        }
        
        @Test
        @DisplayName("다른 사용자의 알림 읽음 처리 시 예외")
        void unauthorizedAccess_throwsException() {
            // given
            Notification notification = createSingleNotification();
            User anotherUser = createAnotherUser();

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(notification.getId(), anotherUser.getUserId()))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotification {
        
        @Test
        @DisplayName("성공")
        void success() {
            // given
            Notification notification = createSingleNotification();

            // when
            notificationService.deleteNotification(testUser.getUserId(), notification.getId());

            // then
            boolean exists = notificationRepository.existsById(notification.getId());
            assertThat(exists).isFalse();
        }
    }

    private void createTestNotifications(int count) {
        for (int i = 0; i < count; i++) {
            notificationService.createNotification(testUser, Type.CHAT, "테스트" + i);
        }
    }

    private Notification createSingleNotification() {
        notificationService.createNotification(testUser, Type.CHAT, "단일 알림 테스트");
        return notificationRepository.findAll().get(0);
    }

    private User createAnotherUser() {
        User anotherUser = User.builder()
                .kakaoId(67890L)
                .nickname("다른사용자")
                .profileImage("another-profile.jpg")
                .status(Status.ACTIVE)
                .build();
        return userRepository.save(anotherUser);
    }
}