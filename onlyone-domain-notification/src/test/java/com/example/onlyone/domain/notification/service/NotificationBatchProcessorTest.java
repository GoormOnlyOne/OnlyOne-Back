package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.sse.service.SseEventSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import static com.example.onlyone.domain.notification.fixture.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationBatchProcessor 단위 테스트")
class NotificationBatchProcessorTest {

    @InjectMocks private NotificationBatchProcessor batchProcessor;
    @Mock private NotificationRepository notificationRepository;
    @Mock private SseEventSender sseEventSender;
    @Mock private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(batchProcessor, "batchSize", 10);
        ReflectionTestUtils.setField(batchProcessor, "maxQueueSizePerUser", 100);
        ReflectionTestUtils.setField(batchProcessor, "batchTimeoutSeconds", 5);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BlockingQueue<NotificationCreatedEvent>> getPendingQueues() {
        return (Map<Long, BlockingQueue<NotificationCreatedEvent>>)
                ReflectionTestUtils.getField(batchProcessor, "pendingQueues");
    }

    private NotificationCreatedEvent toEvent(Notification notification) {
        return NotificationCreatedEvent.from(notification);
    }

    private void enqueue(Notification notification) {
        NotificationCreatedEvent event = toEvent(notification);
        Long userId = event.userId();
        Map<Long, BlockingQueue<NotificationCreatedEvent>> queues = getPendingQueues();
        BlockingQueue<NotificationCreatedEvent> queue = new LinkedBlockingQueue<>(100);
        queue.offer(event);
        queues.put(userId, queue);
    }

    @Nested
    @DisplayName("이벤트 수신")
    class OnNotificationCreated {

        @Test
        @DisplayName("성공: 온라인 사용자면 큐에 추가된다")
        void success_addedToQueueForOnlineUser() {
            Notification notification = likeNotification(1L, user());
            given(sseEventSender.isUserConnected(DEFAULT_USER_ID)).willReturn(true);

            batchProcessor.onNotificationCreated(toEvent(notification));

            Map<Long, BlockingQueue<NotificationCreatedEvent>> queues = getPendingQueues();
            assertThat(queues).containsKey(DEFAULT_USER_ID);
            assertThat(queues.get(DEFAULT_USER_ID)).hasSize(1);
        }

        @Test
        @DisplayName("성공: 오프라인 사용자면 스킵된다")
        void success_skippedForOfflineUser() {
            Notification notification = likeNotification(1L, user());
            given(sseEventSender.isUserConnected(DEFAULT_USER_ID)).willReturn(false);

            batchProcessor.onNotificationCreated(toEvent(notification));

            assertThat(getPendingQueues()).doesNotContainKey(DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("성공: 종료 중이면 스킵된다")
        void success_skippedWhenShuttingDown() {
            ReflectionTestUtils.setField(batchProcessor, "shuttingDown", true);
            Notification notification = likeNotification(1L, user());

            batchProcessor.onNotificationCreated(toEvent(notification));

            assertThat(getPendingQueues()).doesNotContainKey(DEFAULT_USER_ID);

            ReflectionTestUtils.setField(batchProcessor, "shuttingDown", false);
        }
    }

    @Nested
    @DisplayName("배치 처리")
    class ProcessBatch {

        @Test
        @DisplayName("성공: 큐의 알림이 SSE로 전송된다")
        void success_notificationsSentViaSse() {
            Notification notification = likeNotification(1L, user());
            enqueue(notification);

            given(sseEventSender.isUserConnected(DEFAULT_USER_ID)).willReturn(true);
            given(sseEventSender.sendEvent(eq(DEFAULT_USER_ID), eq("notification"), any(NotificationSseDto.class)))
                    .willReturn(CompletableFuture.completedFuture(true));
            willAnswer(invocation -> {
                Consumer<TransactionStatus> action = invocation.getArgument(0);
                action.accept(null);
                return null;
            }).given(transactionTemplate).executeWithoutResult(any());

            batchProcessor.processBatch();

            then(sseEventSender).should().sendEvent(eq(DEFAULT_USER_ID), eq("notification"), any(NotificationSseDto.class));
            then(notificationRepository).should().markSseSentByIds(List.of(1L));
        }

        @Test
        @DisplayName("성공: SSE 전송 실패 시 sse_sent 갱신하지 않는다")
        void success_noMarkWhenSseFails() {
            Notification notification = likeNotification(1L, user());
            enqueue(notification);

            given(sseEventSender.isUserConnected(DEFAULT_USER_ID)).willReturn(true);
            given(sseEventSender.sendEvent(eq(DEFAULT_USER_ID), eq("notification"), any(NotificationSseDto.class)))
                    .willReturn(CompletableFuture.completedFuture(false));

            batchProcessor.processBatch();

            then(sseEventSender).should().sendEvent(eq(DEFAULT_USER_ID), eq("notification"), any(NotificationSseDto.class));
            then(transactionTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("성공: 빈 큐면 아무것도 하지 않는다")
        void success_nothingWhenEmpty() {
            batchProcessor.processBatch();

            then(sseEventSender).should(never()).sendEvent(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("성공: 연결 해제된 사용자 큐가 정리된다")
        void success_disconnectedUserQueueCleaned() {
            Notification notification = likeNotification(1L, user());
            enqueue(notification);

            given(sseEventSender.isUserConnected(DEFAULT_USER_ID)).willReturn(false);

            batchProcessor.processBatch();

            assertThat(getPendingQueues()).doesNotContainKey(DEFAULT_USER_ID);
            then(sseEventSender).should(never()).sendEvent(anyLong(), anyString(), any());
        }
    }
}
