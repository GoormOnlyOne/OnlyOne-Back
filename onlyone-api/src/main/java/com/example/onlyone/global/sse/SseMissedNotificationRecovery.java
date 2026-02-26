package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.sse.service.SseEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 놓친 알림 복구 — 별도 Bean으로 분리하여 @Transactional 프록시 정상 동작 보장.
 * 동시성 제어는 sseEventExecutor(BoundedVtExecutor, 500 permits)가 담당.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseMissedNotificationRecovery {

    private static final int MAX_RECOVERY_SIZE = 50;
    private static final int SEND_TIMEOUT_SECONDS = 5;

    private final NotificationRepository notificationRepository;
    private final SseEventSender sseEventSender;

    @Transactional
    public void recover(Long userId) {
        try {
            List<NotificationItemDto> missed = notificationRepository
                    .findUnsentNotificationsByUserId(userId, MAX_RECOVERY_SIZE);
            if (missed.isEmpty()) return;

            List<Long> sentIds = sendAllInParallel(userId, missed);

            if (!sentIds.isEmpty()) {
                notificationRepository.markSseSentByIds(sentIds);
            }
            log.debug("놓친 알림 복구: userId={}, sent={}/{}", userId, sentIds.size(), missed.size());
        } catch (Exception e) {
            log.warn("놓친 알림 복구 실패: userId={}", userId, e);
        }
    }

    /**
     * 모든 알림을 병렬 전송하고, 성공한 ID 목록을 반환한다.
     * 타임아웃을 적용하여 send 블로킹으로 인한 스레드 점유를 방지.
     */
    private List<Long> sendAllInParallel(Long userId, List<NotificationItemDto> missed) {
        List<CompletableFuture<Long>> futures = missed.stream()
                .map(item -> sseEventSender.sendEvent(userId, "notification", item)
                        .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .thenApply(success -> success ? item.notificationId() : null)
                        .exceptionally(ex -> null))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }
}
