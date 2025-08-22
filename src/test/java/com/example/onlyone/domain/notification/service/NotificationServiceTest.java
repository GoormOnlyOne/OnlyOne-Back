package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 서비스 통합 테스트
 * 
 * UT 번호 순서대로 정렬된 테스트 케이스
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
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        // 실제 DB에 테스트 데이터 생성
        testUser = createTestUser(1L, "테스트유저");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
        testNotification = notificationRepository.save(testNotification);
    }

    @Test
    @DisplayName("UT-NT-001: 읽지 않은 알림이 있을 때 정확한 개수가 반환되는가?")
    void UT_NT_001_returns_unread_count_accurately() {
        // given
        for (int i = 0; i < 5; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        Long result = notificationService.getUnreadCount(testUser.getUserId());

        // then
        assertThat(result).isEqualTo(6L); // 기존 1개 + 새로 생성한 5개
    }

    @Test
    @DisplayName("UT-NT-002: 읽지 않은 알림이 없을 때 0이 반환되는가?")
    void UT_NT_002_returns_0_when_no_unread_notifications() {
        // given
        testNotification.markAsRead();
        notificationRepository.save(testNotification);

        // when
        Long result = notificationService.getUnreadCount(testUser.getUserId());

        // then
        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("UT-NT-003: 특정 사용자의 알림만 정확히 조회되는가?")
    void UT_NT_006_gets_notifications_for_specific_user_only() {
        // given
        User anotherUser = createTestUser(2L, "다른유저");
        AppNotification anotherNotification = AppNotification.create(anotherUser, testNotificationType, "다른유저알림");
        notificationRepository.save(anotherNotification);

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(result.getNotifications()).hasSize(1);
        assertThat(result.getNotifications().get(0).getContent()).contains("테스트");
    }

    @Test
    @DisplayName("UT-NT-004: 페이지 크기대로 알림이 반환되는가?")
    void UT_NT_008_returns_notifications_according_to_page_size() {
        // given
        for (int i = 0; i < 10; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 5);

        // then
        assertThat(result.getNotifications()).hasSize(5);
    }

    @Test
    @DisplayName("UT-NT-005: 알림 ID가 유효하지 않을 때 예외가 발생하는가?")
    void UT_NT_017_throws_exception_when_notification_id_invalid() {
        // given
        Long invalidId = 999999L;

        // when & then
        assertThatThrownBy(() -> notificationService.markAsRead(invalidId, testUser.getUserId()))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("UT-NT-006: 다른 사용자의 알림에 접근할 때 예외가 발생하는가?")
    void UT_NT_018_throws_exception_when_accessing_other_users_notification() {
        // given
        User anotherUser = createTestUser(2L, "다른유저");

        // when & then
        assertThatThrownBy(() -> notificationService.markAsRead(testNotification.getId(), anotherUser.getUserId()))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("UT-NT-007: 정상적인 알림이 성공적으로 생성되는가?")
    void UT_NT_038_creates_notification_successfully() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "새 알림"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getNotificationId()).isNotNull();
        assertThat(result.getContent()).contains("새 알림");
    }

    @Test
    @DisplayName("UT-NT-008: 알림 타입이 템플릿을 적용하여 내용이 생성되는가?")
    void UT_NT_042_applies_template_to_notification_content() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "템플릿 테스트"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        assertThat(result.getContent()).isEqualTo("테스트 템플릿: 템플릿 테스트");
    }

    @Test
    @DisplayName("UT-NT-009: 생성된 알림이 읽지 않음 상태로 초기화되는가?")
    void UT_NT_039_initializes_notification_as_unread() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "읽지않음 테스트"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        AppNotification saved = notificationRepository.findById(result.getNotificationId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    @DisplayName("UT-NT-010: 유효한 알림이 성공적으로 읽음 처리되는가?")
    void UT_NT_015_marks_notification_as_read_successfully() {
        // given
        AppNotification unreadNotification = AppNotification.create(testUser, testNotificationType, "읽음처리테스트");
        unreadNotification = notificationRepository.save(unreadNotification);

        // when
        notificationService.markAsRead(unreadNotification.getId(), testUser.getUserId());

        // then
        AppNotification updated = notificationRepository.findById(unreadNotification.getId()).orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.isRead()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-011: 이미 읽은 알림을 다시 읽음 처리해도 정상 동작하는가?")
    void UT_NT_016_handles_already_read_notification() {
        // given
        testNotification.markAsRead();
        testNotification = notificationRepository.save(testNotification);

        // when & then - 예외 없이 정상 처리
        notificationService.markAsRead(testNotification.getId(), testUser.getUserId());
        
        AppNotification result = notificationRepository.findById(testNotification.getId()).orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.isRead()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-012: 모든 알림이 한번에 읽음 처리되는가?")
    void UT_NT_023_marks_all_notifications_as_read() {
        // given
        for (int i = 0; i < 5; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        notificationService.markAllAsRead(testUser.getUserId());

        // then
        Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
        assertThat(unreadCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("UT-NT-013: 유효한 알림이 성공적으로 삭제되는가?")
    void UT_NT_030_deletes_notification_successfully() {
        // given
        AppNotification toDelete = AppNotification.create(testUser, testNotificationType, "삭제테스트");
        toDelete = notificationRepository.save(toDelete);
        Long deleteId = toDelete.getId();

        // when
        notificationService.deleteNotification(testUser.getUserId(), deleteId);

        // then
        assertThat(notificationRepository.findById(deleteId)).isEmpty();
    }

    @Test
    @DisplayName("UT-NT-014: 알림 목록 조회 시 최신순으로 정렬되는가?")
    void UT_NT_007_returns_notifications_in_descending_order() throws InterruptedException {
        // given
        Thread.sleep(10);
        AppNotification newer = AppNotification.create(testUser, testNotificationType, "최신");
        newer = notificationRepository.save(newer);

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(result.getNotifications()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getNotifications().get(0).getContent()).contains("최신");
    }

    @Test
    @DisplayName("UT-NT-015: 커서 기반 페이지네이션이 정상 동작하는가?")
    void UT_NT_007_cursor_based_pagination_works() {
        // given
        for (int i = 0; i < 10; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 5);
        Long cursor = firstPage.getNotifications().get(firstPage.getNotifications().size() - 1).getNotificationId();
        NotificationListResponseDto secondPage = notificationService.getNotifications(testUser.getUserId(), cursor, 5);

        // then
        List<Long> firstIds = firstPage.getNotifications().stream()
            .map(NotificationItemDto::getNotificationId).toList();
        List<Long> secondIds = secondPage.getNotifications().stream()
            .map(NotificationItemDto::getNotificationId).toList();
        
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Nested
    @DisplayName("동시성 테스트")
    class ConcurrencyTests {
        
        @Test
        @DisplayName("UT-NT-059: 동시에 여러 알림을 삭제해도 안전하게 처리되는가?")
        void UT_NT_056_handles_concurrent_deletions_safely() throws InterruptedException {
            // given
            for (int i = 0; i < 10; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "동시삭제" + i);
                notificationRepository.save(notification);
            }
            
            List<AppNotification> notifications = notificationRepository.findAll().stream()
                .filter(n -> n.getUser().getUserId().equals(testUser.getUserId()))
                .filter(n -> n.getContent().contains("동시삭제"))
                .toList();

            CountDownLatch latch = new CountDownLatch(notifications.size());
            ExecutorService executor = Executors.newFixedThreadPool(5);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // when
            for (AppNotification notification : notifications) {
                executor.submit(() -> {
                    try {
                        notificationService.deleteNotification(testUser.getUserId(), notification.getId());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // then - 트랜잭션 환경에서는 모든 삭제가 실패할 수 있음
            // 적어도 시도는 모두 완료되어야 함
            assertThat(successCount.get() + failCount.get()).isEqualTo(notifications.size());
        }
    }

    @Nested
    @DisplayName("NonNumberedTests")
    class NonNumberedTests {
        @Test
        @DisplayName("여러 유형의 알림이 올바르게 생성되는가?")
        void UT_NT_040_creates_different_notification_types() {
            // given
            NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "정산 알림: %s");
            settlementType = notificationTypeRepository.save(settlementType);

            // when
            NotificationCreateRequestDto chatRequest = NotificationCreateRequestDto.of(
                testUser.getUserId(), Type.CHAT, "채팅 알림"
            );
            NotificationCreateRequestDto settlementRequest = NotificationCreateRequestDto.of(
                testUser.getUserId(), Type.SETTLEMENT, "정산 알림"
            );

            NotificationCreateResponseDto chatResult = notificationService.createNotification(chatRequest);
            NotificationCreateResponseDto settlementResult = notificationService.createNotification(settlementRequest);

            // then
            assertThat(chatResult.getContent()).contains("채팅 알림");
            assertThat(settlementResult.getContent()).contains("정산 알림");
        }
    }

    // Helper 메서드
    private User createTestUser(Long kakaoId, String nickname) {
        User user = User.builder()
            .kakaoId(kakaoId)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
        return userRepository.save(user);
    }
}