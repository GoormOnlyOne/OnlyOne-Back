package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.sse.service.SseEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * SSE 놓친 알림 복구 — 별도 Bean으로 분리하여 @Transactional 프록시 정상 동작 보장
 * 동시 복구 요청을 세마포어로 제한하여 DB 커넥션 풀 고갈 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseMissedNotificationRecovery {

    private static final int MAX_RECOVERY_SIZE = 50;
    private static final int MAX_CONCURRENT_RECOVERY = 30;

    private final NotificationRepository notificationRepository;
    private final SseEventSender sseEventSender;
    private final Semaphore recoverySemaphore = new Semaphore(MAX_CONCURRENT_RECOVERY);

    @Transactional
    public void recover(Long userId) {
        if (!recoverySemaphore.tryAcquire()) {
            log.debug("놓친 알림 복구 스킵 (동시 한도 초과): userId={}", userId);
            return;
        }
        try {
            List<NotificationItemDto> missed = notificationRepository
                    .findUnsentNotificationsByUserId(userId, MAX_RECOVERY_SIZE);
            if (missed.isEmpty()) return;

            List<Long> sentIds = missed.stream()
                    .filter(item -> {
                        try {
                            return Boolean.TRUE.equals(
                                    sseEventSender.sendEvent(userId, "notification", item).join());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(NotificationItemDto::notificationId)
                    .toList();

            if (!sentIds.isEmpty()) {
                notificationRepository.markSseSentByIds(sentIds);
            }
            log.debug("놓친 알림 복구: userId={}, sent={}/{}", userId, sentIds.size(), missed.size());
        } catch (Exception e) {
            log.warn("놓친 알림 복구 실패: userId={}", userId, e);
        } finally {
            recoverySemaphore.release();
        }
    }
}
