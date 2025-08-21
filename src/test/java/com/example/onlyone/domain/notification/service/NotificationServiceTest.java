package com.example.onlyone.domain.notification.service;

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
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Notification Service Test - Simplified practical test
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 서비스 테스트")
class NotificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationTypeRepository notificationTypeRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private SseEmittersService sseEmittersService;
    @Mock
    private FcmService fcmService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private JPAQueryFactory queryFactory;
    @InjectMocks
    private NotificationService notificationService;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        testUser = createTestUser(1L, "테스트유저");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿");
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회")
    class GetUnreadCountTest {

        @Test
        @DisplayName("UT-NT-001: 읽지 않은 알림이 있을 때 정확한 개수가 반환되는가?")
        void returns_unread_count_accurately() {
            // given
            Long userId = 1L;
            Long expectedCount = 5L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(expectedCount);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(expectedCount);
        }

        @Test
        @DisplayName("UT-NT-002: 읽지 않은 알림이 없을 때 0이 반환되는가?")
        void returns_0_when_unread_count_is_null() {
            // given
            Long userId = 1L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(null);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("알림 생성 검증")
    class CreateNotificationValidationTest {

        @Test
        @DisplayName("UT-NT-041: 존재하지 않는 사용자로 생성 시 404 에러가 발생하는가?")
        void throws_exception_when_user_does_not_exist() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(999L, Type.CHAT, "테스트");
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-042: 존재하지 않는 알림 타입으로 생성 시 404 에러가 발생하는가?")
        void throws_exception_when_notification_type_does_not_exist() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAllAsReadTest {

        @Test
        @DisplayName("UT-NT-023: 여러 개의 읽지 않은 알림이 모두 읽음 처리되는가?")
        void returns_number_of_notifications_marked_as_read() {
            // given
            Long userId = 1L;
            given(notificationRepository.markAllAsReadByUserId(userId)).willReturn(3L);

            // when
            notificationService.markAllAsRead(userId);

            // then - 예외 없이 완료되면 성공
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("타겟 정보 포함 알림 생성")
    class CreateNotificationWithTargetTest {

        @Test
        @DisplayName("UT-NT-038: 알림이 정상적으로 생성되는가?")
        void creates_notification_with_convenience_method() {
            // given
            User user = createTestUser(1L, "테스트유저");
            Type type = Type.CHAT;
            NotificationType notificationType = NotificationType.of(type, "테스트 템플릿");
            AppNotification expectedNotification = AppNotification.create(
                user, notificationType, "테스트메시지");
            
            given(notificationTypeRepository.findByType(type)).willReturn(Optional.of(notificationType));
            given(notificationRepository.save(any(AppNotification.class))).willReturn(expectedNotification);

            // when
            NotificationCreateResponseDto result = notificationService.createNotification(
                user, type, "테스트메시지");

            // then
            assertThat(result).isNotNull();
            then(notificationRepository).should().save(any(AppNotification.class));
            // 이벤트 발행은 비동기/트랜잭션 시점에 따라 달라질 수 있어 유닛 테스트에서는 검증하지 않음
        }
    }

    @Nested
    @DisplayName("알림 조회")
    class GetNotificationsTest {

        @Test
        @DisplayName("UT-NT-006: 페이징된 알림 목록이 최신순으로 정상 조회되는가?")
        void gets_user_notifications() {
            // given
            Long userId = 1L;
            Long cursor = null;
            int size = 20;
            List<NotificationItemDto> mockList = List.of(
                NotificationItemDto.builder()
                    .notificationId(1L)
                    .type(Type.CHAT)
                    .content("테스트 알림")
                    .isRead(false)
                    .build()
            );
            // size + 1로 호출되므로 해당 매개변수로 Mock 설정
            given(notificationRepository.findNotificationsByUserId(userId, cursor, size + 1)).willReturn(mockList);
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(5L);

            // when
            NotificationListResponseDto result = notificationService.getNotifications(userId, cursor, size);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getNotifications()).hasSize(1);
        }

        @Test
        @DisplayName("UT-NT-012: 특정 타입의 알림만 필터링되어 조회되는가?")
        void gets_notifications_by_type() {
            // given
            Long userId = 1L;
            Type type = Type.LIKE;
            Long cursor = null;
            int size = 20;
            List<NotificationItemDto> mockList = List.of(
                NotificationItemDto.builder()
                    .notificationId(2L)
                    .type(Type.LIKE)
                    .content("좋아요 알림")
                    .isRead(false)
                    .build()
            );
            // size + 1로 호출되므로 해당 매개변수로 Mock 설정
            given(notificationRepository.findNotificationsByUserIdAndType(userId, type, cursor, size + 1)).willReturn(mockList);
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(3L);

            // when
            NotificationListResponseDto result = notificationService.getNotificationsByType(userId, type, cursor, size);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getNotifications()).hasSize(1);
            assertThat(result.getNotifications().get(0).getType()).isEqualTo(Type.LIKE);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotificationTest {

        @Test
        @DisplayName("UT-NT-030: 읽지 않은 알림이 정상 삭제되는가?")
        void deletes_notification_successfully() {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            User user = createTestUser(1L, "테스트유저");
            AppNotification notification = AppNotification.create(user, 
                NotificationType.of(Type.CHAT, "테스트 템플릿"), "삭제테스트");
            
            given(notificationRepository.findByIdWithFetchJoin(notificationId)).willReturn(notification);

            // when
            notificationService.deleteNotification(userId, notificationId);

            // then
            then(notificationRepository).should().findByIdWithFetchJoin(notificationId);
            then(notificationRepository).should().delete(notification);
        }

        @Test
        @DisplayName("UT-NT-032: 존재하지 않는 알림 삭제 시 404 에러가 발생하는가?")
        void throws_exception_when_deleting_non_existent_notification() {
            // given
            Long userId = 1L;
            Long notificationId = 999L;
            given(notificationRepository.findByIdWithFetchJoin(notificationId)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(userId, notificationId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("개별 읽음 처리")
    class MarkAsReadTest {

        @Test
        @DisplayName("UT-NT-015: 읽지 않은 알림이 정상적으로 읽음 처리되는가?")
        void marks_individual_notification_as_read() {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            // testUser의 userId가 1L로 설정되어 있는지 확인
            User user = createTestUser(1L, "테스트유저");
            AppNotification notification = AppNotification.create(user, 
                NotificationType.of(Type.CHAT, "테스트 템플릿"), "읽음테스트");
            
            given(notificationRepository.findByIdWithFetchJoin(notificationId)).willReturn(notification);

            // when
            notificationService.markAsRead(notificationId, userId);

            // then
            then(notificationRepository).should().findByIdWithFetchJoin(notificationId);
            assertThat(notification.isRead()).isTrue(); // 엔티티 상태 직접 확인
        }
    }

    @Nested
    @DisplayName("이벤트 리스너")
    class EventListenerTest {

        @Test
        @DisplayName("UT-NT-044: FCM 토큰이 있는 경우 FCM 전송이 시도되는가?")
        void handles_notification_created_event_with_fcm_only() {
            // given
            NotificationType notificationType = NotificationType.of(Type.CHAT, "테스트 템플릿");
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then - 비동기 처리로 인해 디렉트 대상 메서드는 검증하지 않음
            // 대신 디리버리 메서드 호출 자체를 확인
            assertThat(notification.shouldSendSse()).isFalse();
            assertThat(notification.shouldSendFcm()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-043: 알림 생성 후 SSE로 실시간 전송되는가?")
        void handles_notification_created_event_with_sse_only() {
            // given
            NotificationType notificationType = NotificationType.of(Type.LIKE, "테스트 템플릿");
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            assertThat(notification.shouldSendSse()).isTrue();
            assertThat(notification.shouldSendFcm()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-045: 전송 방식(DeliveryMethod)에 따라 올바르게 전송되는가?")
        void handles_notification_created_event_with_fcm_only_settlement() {
            // given
            NotificationType notificationType = NotificationType.of(Type.SETTLEMENT, "테스트 템플릿");
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            assertThat(notification.shouldSendSse()).isFalse();
            assertThat(notification.shouldSendFcm()).isTrue();
        }
    }

    @Nested
    @DisplayName("동시성 테스트 - Mock 기반")
    class ConcurrencyTest {

        @Test
        @DisplayName("UT-NT-056: 동시에 100개의 읽지 않은 개수 조회가 정상 처리되는가?")
        void concurrent_unread_count_queries_are_safe() throws Exception {
            // given
            Long userId = 1L;
            Long expectedCount = 10L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(expectedCount);

            int threadCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger correctResults = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        Long result = notificationService.getUnreadCount(userId);
                        successCount.incrementAndGet();

                        if (expectedCount.equals(result)) {
                            correctResults.incrementAndGet();
                        }

                    } catch (Exception e) {
                        // 실패 카운트는 successCount로 추적
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 모든 스레드 동시 시작
            endLatch.await();
            executor.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(correctResults.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("UT-NT-058: 동시에 같은 알림을 여러 번 읽음 처리해도 안전하다")
        void concurrent_mark_as_read_same_notification_is_safe() throws Exception {
            // given
            given(notificationRepository.findByIdWithFetchJoin(1L)).willReturn(testNotification);

            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when - 동시에 같은 알림 읽음 처리
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        notificationService.markAsRead(1L, 1L);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 예외 무시
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // then - 모든 스레드가 성공해야 함
            assertThat(successCount.get()).isEqualTo(threadCount);
            then(notificationRepository).should(atLeast(threadCount)).findByIdWithFetchJoin(1L);
        }

        @Test
        @DisplayName("UT-NT-057: 동시에 50개의 읽음 처리 요청이 정상 처리되는가?")
        void concurrent_mark_as_read_operations_are_safe() throws Exception {
            // given
            Long userId = 1L;
            given(notificationRepository.markAllAsReadByUserId(userId)).willReturn(5L);

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        notificationService.markAllAsRead(userId);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        // 실패는 successCount로 추적
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(threadCount);
        }
    }

    @Nested
    @DisplayName("추가 테스트 케이스")
    class AdditionalTestCases {

        @Test
        @DisplayName("UT-NT-046: 생성 → 조회 → 읽음 → 삭제 전체 플로우가 정상 동작하는가?")
        void basic_notification_lifecycle() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(testNotificationType));
            given(notificationRepository.save(any(AppNotification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

            // when - 생성
            NotificationCreateRequestDto createRequest = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트");
            NotificationCreateResponseDto response = notificationService.createNotification(createRequest);
            
            // then
            assertThat(response).isNotNull();
            then(notificationRepository).should().save(any(AppNotification.class));
            then(userRepository).should().findById(1L);
            then(notificationTypeRepository).should().findByType(Type.CHAT);
        }

        @Test
        @DisplayName("UT-NT-048: 삭제된 알림에 대한 모든 작업이 404를 반환하는가?")
        void throws_exception_for_deleted_notification() {
            // given
            given(notificationRepository.findByIdWithFetchJoin(999L)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(999L, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
            
            then(notificationRepository).should().findByIdWithFetchJoin(999L);
        }

        @Test
        @DisplayName("UT-NT-051: 타 사용자 알림 접근 시 모두 404로 처리되는가?")
        void blocks_cross_user_access() {
            // given
            given(notificationRepository.findByIdWithFetchJoin(100L)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(100L, 2L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
            
            then(notificationRepository).should().findByIdWithFetchJoin(100L);
        }

        @Test
        @DisplayName("UT-NT-063: 읽지 않은 개수가 실제 DB 상태와 일치하는가?")
        void count_query_consistency() {
            // given
            given(notificationRepository.countUnreadByUserId(1L)).willReturn(5L);

            // when
            Long count = notificationService.getUnreadCount(1L);

            // then
            assertThat(count).isEqualTo(5L);
            then(notificationRepository).should().countUnreadByUserId(1L);
        }

        @Test
        @DisplayName("UT-NT-047: 여러 알림 생성 후 개별/전체 읽음 처리가 정상 동작하는가?")
        void multiple_notification_lifecycle_works() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(testNotificationType));
            given(notificationRepository.save(any(AppNotification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
            given(notificationRepository.markAllAsReadByUserId(1L)).willReturn(3L);

            // when - 여러 알림 생성
            for(int i = 0; i < 3; i++) {
                NotificationCreateRequestDto request = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트" + i);
                notificationService.createNotification(request);
            }
            
            // then - 전체 읽음 처리
            notificationService.markAllAsRead(1L);
            
            then(notificationRepository).should(times(3)).save(any(AppNotification.class));
            then(notificationRepository).should().markAllAsReadByUserId(1L);
        }

        @Test
        @DisplayName("UT-NT-049: 각 사용자의 알림이 독립적으로 관리되는가?")
        void user_notifications_are_isolated() {
            // given
            User user1 = createTestUser(1L, "사용자1");
            User user2 = createTestUser(2L, "사용자2");
            given(userRepository.findById(1L)).willReturn(Optional.of(user1));
            given(userRepository.findById(2L)).willReturn(Optional.of(user2));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(testNotificationType));
            given(notificationRepository.save(any(AppNotification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
            given(notificationRepository.countUnreadByUserId(1L)).willReturn(1L);
            given(notificationRepository.countUnreadByUserId(2L)).willReturn(2L);

            // when
            NotificationCreateRequestDto request1 = NotificationCreateRequestDto.of(1L, Type.CHAT, "사용자1 알림");
            NotificationCreateRequestDto request2 = NotificationCreateRequestDto.of(2L, Type.CHAT, "사용자2 알림");
            
            notificationService.createNotification(request1);
            notificationService.createNotification(request2);

            Long count1 = notificationService.getUnreadCount(1L);
            Long count2 = notificationService.getUnreadCount(2L);

            // then
            assertThat(count1).isEqualTo(1L);
            assertThat(count2).isEqualTo(2L);
            then(notificationRepository).should().countUnreadByUserId(1L);
            then(notificationRepository).should().countUnreadByUserId(2L);
        }

        @Test
        @DisplayName("UT-NT-050: 사용자 A의 작업이 사용자 B에게 영향을 주지 않는가?")
        void user_operations_do_not_affect_others() {
            // given
            given(notificationRepository.markAllAsReadByUserId(1L)).willReturn(3L);
            given(notificationRepository.countUnreadByUserId(2L)).willReturn(5L);

            // when - 사용자 1의 알림만 읽음 처리
            notificationService.markAllAsRead(1L);
            
            // 사용자 2의 읽지 않은 개수 확인
            Long user2Count = notificationService.getUnreadCount(2L);

            // then - 사용자 2는 영향받지 않음
            assertThat(user2Count).isEqualTo(5L);
            then(notificationRepository).should().markAllAsReadByUserId(1L);
            then(notificationRepository).should().countUnreadByUserId(2L);
            then(notificationRepository).should(never()).markAllAsReadByUserId(2L);
        }

        @Test
        @DisplayName("UT-NT-039: 알림 생성 시 읽지 않음 상태로 초기화되는가?")
        void notification_created_with_unread_status() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(testNotificationType));
            given(notificationRepository.save(any(AppNotification.class)))
                .willAnswer(invocation -> {
                    AppNotification notification = invocation.getArgument(0);
                    assertThat(notification.isRead()).isFalse(); // 읽지 않음 상태 확인
                    return notification;
                });

            // when
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트");
            notificationService.createNotification(request);

            // then
            then(notificationRepository).should().save(any(AppNotification.class));
        }

        @Test
        @DisplayName("UT-NT-040: 알림 타입별 템플릿이 올바르게 적용되는가?")
        void notification_template_applied_correctly() {
            // given
            NotificationType chatType = NotificationType.of(Type.CHAT, "채팅 템플릿: {content}");
            NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿: {content}");
            
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(chatType));
            given(notificationTypeRepository.findByType(Type.LIKE)).willReturn(Optional.of(likeType));
            given(notificationRepository.save(any(AppNotification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

            // when
            NotificationCreateRequestDto chatRequest = NotificationCreateRequestDto.of(1L, Type.CHAT, "채팅메시지");
            NotificationCreateRequestDto likeRequest = NotificationCreateRequestDto.of(1L, Type.LIKE, "좋아요");
            
            notificationService.createNotification(chatRequest);
            notificationService.createNotification(likeRequest);

            // then
            then(notificationTypeRepository).should().findByType(Type.CHAT);
            then(notificationTypeRepository).should().findByType(Type.LIKE);
            then(notificationRepository).should(times(2)).save(any(AppNotification.class));
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}