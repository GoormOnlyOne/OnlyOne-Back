package com.example.onlyone.domain.notification;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification System H2 Integration Test
 * - Real database integration test
 * - Transaction behavior verification
 * - Service layer test
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("알림 시스템 통합 테스트")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationH2IntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationTypeRepository notificationTypeRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private List<NotificationType> notificationTypes;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = userRepository.save(
            User.builder()
                .kakaoId(12345L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .fcmToken("test_fcm_token_integration")
                .build()
        );

        // Initialize notification types
        initializeNotificationTypes();
    }

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("알림 생성 통합 테스트")
    @Transactional
    class NotificationCreationIntegrationTest {

        @Test
        @Order(1)
        @DisplayName("서비스를 통해 여러 타입의 알림을 생성한다")
        @Transactional
        void creates_multiple_type_notifications_through_service() {
            // given & when
            notificationService.createNotification(testUser, Type.CHAT, "사용자1");
            notificationService.createNotification(testUser, Type.LIKE, "사용자2");
            notificationService.createNotification(testUser, Type.COMMENT, "사용자3", "좋은 글이네요!");
            notificationService.createNotification(testUser, Type.REFEED, "사용자4");

            // then
            List<AppNotification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(4);
            assertThat(notifications)
                .extracting(n -> n.getNotificationType().getType())
                .containsExactlyInAnyOrder(
                    Type.CHAT,
                    Type.LIKE,
                    Type.COMMENT,
                    Type.REFEED
                );
        }

        @Test
        @Order(2)
        @DisplayName("타겟 정보를 포함한 알림을 생성한다")
        @Transactional
        void creates_notification_with_target_info() {
            // given & when
            // Chat notification with chat room ID
            notificationService.createNotificationWithTarget(testUser, Type.CHAT, 123L, "사용자1");
            
            // Like notification with post ID
            notificationService.createNotificationWithTarget(testUser, Type.LIKE, 456L, "사용자2");
            
            // Comment notification with post ID
            notificationService.createNotificationWithTarget(testUser, Type.COMMENT, 789L, "사용자3", "좋아요!");

            // then
            List<AppNotification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(3);
            
            // Verify target info
            AppNotification chatNotif = notifications.stream()
                .filter(n -> n.getNotificationType().getType() == Type.CHAT)
                .findFirst().orElseThrow();
            assertThat(chatNotif.getTargetType()).isEqualTo("CHAT");
            assertThat(chatNotif.getTargetId()).isEqualTo(123L);
            
            AppNotification likeNotif = notifications.stream()
                .filter(n -> n.getNotificationType().getType() == Type.LIKE)
                .findFirst().orElseThrow();
            assertThat(likeNotif.getTargetType()).isEqualTo("POST");
            assertThat(likeNotif.getTargetId()).isEqualTo(456L);
        }
    }

    @Nested
    @DisplayName("알림 조회 통합 테스트")
    class NotificationQueryIntegrationTest {

        @BeforeEach
        void createTestNotifications() {
            // Create 20 test notifications
            for (int i = 0; i < 20; i++) {
                Type type = i % 2 == 0 ? Type.CHAT : Type.LIKE;
                notificationService.createNotification(testUser, type, "테스트" + i);
            }
        }

        @Test
        @Order(3)
        @DisplayName("읽지 않은 알림 개수를 정확히 조회한다")
        void queries_unread_notification_count_accurately() {
            // given - Mark some notifications as read
            List<AppNotification> notifications = notificationRepository.findAll();
            for (int i = 0; i < 5; i++) {
                notifications.get(i).markAsRead();
            }
            notificationRepository.saveAll(notifications);

            // when
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());

            // then
            assertThat(unreadCount).isEqualTo(15L);
        }
    }

    @Nested
    @DisplayName("알림 읽음/삭제 통합 테스트")
    class NotificationUpdateIntegrationTest {

        private Long notificationId;

        @BeforeEach
        void createTestNotification() {
            AppNotification notification = notificationRepository.save(
                AppNotification.create(
                    testUser,
                    notificationTypes.get(0),
                    "테스트"
                )
            );
            notificationId = notification.getId();
        }

        @Test
        @Order(4)
        @DisplayName("개별 알림을 읽음 처리한다")
        void marks_individual_notification_as_read() {
            // when
            notificationService.markAsRead(notificationId, testUser.getUserId());

            // then
            AppNotification updated = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(updated.isRead()).isTrue();
        }

        @Test
        @Order(5)
        @DisplayName("모든 알림을 읽음 처리한다")
        void marks_all_notifications_as_read() {
            // given - Create additional notifications
            for (int i = 0; i < 5; i++) {
                notificationService.createNotification(testUser, Type.CHAT, "추가" + i);
            }

            // when
            notificationService.markAllAsRead(testUser.getUserId());

            // then
            Long unreadCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
            assertThat(unreadCount).isEqualTo(0);
        }

        @Test
        @Order(6)
        @DisplayName("알림을 삭제한다")
        void deletes_notification() {
            // when
            notificationService.deleteNotification(testUser.getUserId(), notificationId);

            // then
            boolean exists = notificationRepository.existsById(notificationId);
            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("동시성 통합 테스트")
    class ConcurrencyIntegrationTest {

        @Test
        @Order(7)
        @DisplayName("동시에 여러 알림을 생성해도 데이터 일관성이 유지된다")
        void maintains_data_consistency_with_concurrent_notification_creation() throws Exception {
            // given
            int threadCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        notificationService.createNotification(
                            testUser, 
                            Type.CHAT, 
                            "동시성테스트" + index
                        );
                        successCount.incrementAndGet();
                        
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // then
            Thread.sleep(1000); // Wait for transaction completion
            List<AppNotification> notifications = notificationRepository.findAll();
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(notifications).hasSizeGreaterThanOrEqualTo(threadCount);
        }

        @Test
        @Order(8)
        @DisplayName("동시에 읽음 처리를 해도 정확한 카운트가 유지된다")
        void maintains_accurate_count_with_concurrent_read_marking() throws Exception {
            // given - Create 10 notifications
            List<Long> notificationIds = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                AppNotification notification = notificationRepository.save(
                    AppNotification.create(testUser, notificationTypes.get(0), "테스트" + i)
                );
                notificationIds.add(notification.getId());
            }

            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when - Each thread marks different notification as read
            for (int i = 0; i < threadCount; i++) {
                final Long notificationId = notificationIds.get(i);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        notificationService.markAsRead(notificationId, testUser.getUserId());
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // then
            Thread.sleep(500);
            Long unreadCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
            assertThat(unreadCount).isEqualTo(0);
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private void initializeNotificationTypes() {
        notificationTypes = new ArrayList<>();
        
        // Initialize all notification types
        for (Type type : Type.values()) {
            NotificationType notificationType = notificationTypeRepository.findByType(type)
                .orElseGet(() -> {
                    NotificationType newType = NotificationType.of(type, getTemplateForType(type), getDeliveryMethodForType(type));
                    return notificationTypeRepository.save(newType);
                });
            notificationTypes.add(notificationType);
        }
    }

    private String getTemplateForType(Type type) {
        return switch (type) {
            case CHAT -> "%s sent a message.";
            case SETTLEMENT -> "%s created a settlement.";
            case LIKE -> "%s liked your post.";
            case COMMENT -> "%s commented: %s";
            case REFEED -> "%s refeeded your post.";
        };
    }

    private DeliveryMethod getDeliveryMethodForType(Type type) {
        return switch (type) {
            case CHAT -> DeliveryMethod.BOTH;
            case SETTLEMENT, LIKE, COMMENT, REFEED -> DeliveryMethod.SSE_ONLY;
        };
    }
}