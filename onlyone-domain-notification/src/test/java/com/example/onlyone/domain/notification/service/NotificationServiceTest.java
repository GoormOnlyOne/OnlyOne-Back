package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationActionDto;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.sse.service.SseEventSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @InjectMocks
    private NotificationService notificationService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SseEventSender sseEventSender;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .userId(1L)
                .kakaoId(1000L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .build();

        otherUser = User.builder()
                .userId(2L)
                .kakaoId(2000L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(User user) {
        UserPrincipal principal = UserPrincipal.from(user);
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private NotificationItemDto createNotificationItemDto(Long id) {
        return new NotificationItemDto(
                id,
                "알림 내용 " + id,
                NotificationType.LIKE,
                false,
                LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("알림 목록 조회")
    class GetNotifications {

        @Test
        @DisplayName("성공: 알림 목록이 반환된다")
        void success_returnsNotificationList() {
            // given
            setSecurityContext(testUser);
            NotificationQueryDto queryDto = new NotificationQueryDto(null, 10);

            List<NotificationItemDto> items = List.of(
                    createNotificationItemDto(3L),
                    createNotificationItemDto(2L),
                    createNotificationItemDto(1L)
            );

            when(notificationRepository.findNotificationsByUserId(1L, null, 11)).thenReturn(items);
            when(notificationRepository.countUnreadByUserId(1L)).thenReturn(2L);

            // when
            NotificationListResponseDto result = notificationService.getNotifications(queryDto);

            // then
            assertThat(result.notifications()).hasSize(3);
            assertThat(result.hasMore()).isFalse();
            assertThat(result.unreadCount()).isEqualTo(2L);
            assertThat(result.cursor()).isEqualTo(1L);
        }

        @Test
        @DisplayName("성공: size가 30을 초과하면 30으로 제한된다")
        void success_sizeIsCappedAt30() {
            // given
            setSecurityContext(testUser);
            NotificationQueryDto queryDto = new NotificationQueryDto(null, 50);

            List<NotificationItemDto> items = new ArrayList<>();
            when(notificationRepository.findNotificationsByUserId(1L, null, 31)).thenReturn(items);
            when(notificationRepository.countUnreadByUserId(1L)).thenReturn(0L);

            // when
            notificationService.getNotifications(queryDto);

            // then
            verify(notificationRepository).findNotificationsByUserId(1L, null, 31);
        }

        @Test
        @DisplayName("성공: hasMore가 올바르게 설정된다")
        void success_hasMoreIsSetCorrectly() {
            // given
            setSecurityContext(testUser);
            NotificationQueryDto queryDto = new NotificationQueryDto(null, 2);

            List<NotificationItemDto> items = List.of(
                    createNotificationItemDto(3L),
                    createNotificationItemDto(2L),
                    createNotificationItemDto(1L)
            );

            when(notificationRepository.findNotificationsByUserId(1L, null, 3)).thenReturn(items);
            when(notificationRepository.countUnreadByUserId(1L)).thenReturn(0L);

            // when
            NotificationListResponseDto result = notificationService.getNotifications(queryDto);

            // then
            assertThat(result.hasMore()).isTrue();
            assertThat(result.notifications()).hasSize(2);
            assertThat(result.cursor()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("읽지 않은 알림 개수")
    class GetUnreadCount {

        @Test
        @DisplayName("성공: 개수가 반환된다")
        void success_returnsUnreadCount() {
            // given
            setSecurityContext(testUser);
            when(notificationRepository.countUnreadByUserId(1L)).thenReturn(5L);

            // when
            Long count = notificationService.getUnreadCount();

            // then
            assertThat(count).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("성공: 알림이 읽음으로 변경된다")
        void success_notificationIsMarkedAsRead() {
            // given
            setSecurityContext(testUser);
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");
            NotificationActionDto actionDto = new NotificationActionDto(1L);

            when(notificationRepository.findByIdWithFetchJoin(1L)).thenReturn(notification);

            // when
            notificationService.markAsRead(actionDto);

            // then
            assertThat(notification.isRead()).isTrue();
        }

        @Test
        @DisplayName("실패: 알림이 존재하지 않으면 NOTIFICATION_NOT_FOUND")
        void fail_notificationNotFound() {
            // given
            setSecurityContext(testUser);
            NotificationActionDto actionDto = new NotificationActionDto(999L);

            when(notificationRepository.findByIdWithFetchJoin(999L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(actionDto))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 다른 사용자의 알림이면 NOTIFICATION_NOT_FOUND")
        void fail_otherUserNotification() {
            // given
            setSecurityContext(testUser);
            Notification notification = Notification.create(otherUser, NotificationType.LIKE, "홍길동");
            NotificationActionDto actionDto = new NotificationActionDto(1L);

            when(notificationRepository.findByIdWithFetchJoin(1L)).thenReturn(notification);

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(actionDto))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("모든 알림 읽음")
    class MarkAllAsRead {

        @Test
        @DisplayName("성공: 모든 알림이 읽음으로 변경된다")
        void success_allNotificationsMarkedAsRead() {
            // given
            setSecurityContext(testUser);
            when(notificationRepository.markAllAsReadByUserId(1L)).thenReturn(3L);

            // when
            notificationService.markAllAsRead();

            // then
            verify(notificationRepository).markAllAsReadByUserId(1L);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotification {

        @Test
        @DisplayName("성공: 알림이 삭제된다")
        void success_notificationIsDeleted() {
            // given
            setSecurityContext(testUser);
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");
            NotificationActionDto actionDto = new NotificationActionDto(1L);

            when(notificationRepository.findByIdWithFetchJoin(1L)).thenReturn(notification);

            // when
            notificationService.deleteNotification(actionDto);

            // then
            verify(notificationRepository).delete(notification);
        }

        @Test
        @DisplayName("실패: 다른 사용자의 알림이면 NOTIFICATION_NOT_FOUND")
        void fail_otherUserNotification() {
            // given
            setSecurityContext(testUser);
            Notification notification = Notification.create(otherUser, NotificationType.LIKE, "홍길동");
            NotificationActionDto actionDto = new NotificationActionDto(1L);

            when(notificationRepository.findByIdWithFetchJoin(1L)).thenReturn(notification);

            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(actionDto))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 생성")
    class CreateNotification {

        @Test
        @DisplayName("성공: 알림이 저장되고 온라인 사용자에게 이벤트가 발행된다")
        void success_savedAndEventPublishedForOnlineUser() {
            // given
            NotificationCreateDto createDto = new NotificationCreateDto(testUser, NotificationType.LIKE, new String[]{"홍길동"});

            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sseEventSender.isUserConnected(1L)).thenReturn(true);

            // when
            notificationService.createNotification(createDto);

            // then
            verify(notificationRepository).save(any(Notification.class));
            verify(eventPublisher).publishEvent(any(NotificationCreatedEvent.class));
        }

        @Test
        @DisplayName("성공: 오프라인 사용자면 이벤트가 발행되지 않는다")
        void success_noEventForOfflineUser() {
            // given
            NotificationCreateDto createDto = new NotificationCreateDto(testUser, NotificationType.LIKE, new String[]{"홍길동"});

            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sseEventSender.isUserConnected(1L)).thenReturn(false);

            // when
            notificationService.createNotification(createDto);

            // then
            verify(notificationRepository).save(any(Notification.class));
            verify(eventPublisher, never()).publishEvent(any(NotificationCreatedEvent.class));
        }
    }

    @Nested
    @DisplayName("배치 이벤트 핸들러")
    class HandleNotificationCreated {

        @Test
        @DisplayName("성공: 온라인 사용자면 큐에 추가된다")
        void success_addedToQueueForOnlineUser() {
            // given
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");
            NotificationCreatedEvent event = new NotificationCreatedEvent(notification);

            when(sseEventSender.isUserConnected(1L)).thenReturn(true);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            assertThat(queues).containsKey(1L);
            assertThat(queues.get(1L)).hasSize(1);
            assertThat(queues.get(1L).peek()).isEqualTo(notification);
        }

        @Test
        @DisplayName("성공: 오프라인 사용자면 스킵된다")
        void success_skippedForOfflineUser() {
            // given
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");
            NotificationCreatedEvent event = new NotificationCreatedEvent(notification);

            when(sseEventSender.isUserConnected(1L)).thenReturn(false);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            assertThat(queues).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("성공: 종료 중이면 스킵된다")
        void success_skippedWhenShuttingDown() {
            // given
            ReflectionTestUtils.setField(notificationService, "shuttingDown", true);

            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");
            NotificationCreatedEvent event = new NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            assertThat(queues).doesNotContainKey(1L);

            // cleanup
            ReflectionTestUtils.setField(notificationService, "shuttingDown", false);
        }
    }

    @Nested
    @DisplayName("배치 처리")
    class ProcessBatchNotifications {

        @Test
        @DisplayName("성공: 큐의 알림이 SSE로 전송된다")
        void success_notificationsSentViaSse() {
            // given
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");

            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            BlockingQueue<Notification> queue = new LinkedBlockingQueue<>(100);
            queue.offer(notification);
            queues.put(1L, queue);

            when(sseEventSender.isUserConnected(1L)).thenReturn(true);
            when(sseEventSender.sendEvent(eq(1L), eq("notification"), any(Notification.class)))
                    .thenReturn(CompletableFuture.completedFuture(true));

            // when
            notificationService.processBatchNotifications();

            // then
            verify(sseEventSender).sendEvent(eq(1L), eq("notification"), eq(notification));
        }

        @Test
        @DisplayName("성공: 비어있으면 아무것도 하지 않는다")
        void success_nothingWhenEmpty() {
            // given
            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            assertThat(queues).isEmpty();

            // when
            notificationService.processBatchNotifications();

            // then
            verify(sseEventSender, never()).sendEvent(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("성공: 연결 해제된 사용자 큐가 정리된다")
        void success_disconnectedUserQueueCleaned() {
            // given
            Notification notification = Notification.create(testUser, NotificationType.LIKE, "홍길동");

            @SuppressWarnings("unchecked")
            Map<Long, BlockingQueue<Notification>> queues =
                    (Map<Long, BlockingQueue<Notification>>) ReflectionTestUtils.getField(notificationService, "userNotificationQueues");
            BlockingQueue<Notification> queue = new LinkedBlockingQueue<>(100);
            queue.offer(notification);
            queues.put(1L, queue);

            when(sseEventSender.isUserConnected(1L)).thenReturn(false);

            // when
            notificationService.processBatchNotifications();

            // then
            assertThat(queues).doesNotContainKey(1L);
            verify(sseEventSender, never()).sendEvent(anyLong(), anyString(), any());
        }
    }
}
