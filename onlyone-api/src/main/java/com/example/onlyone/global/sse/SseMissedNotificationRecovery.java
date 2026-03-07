package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import com.example.onlyone.sse.service.SseConnectionManager;
import com.example.onlyone.sse.service.SseEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 놓친 알림 복구 — 별도 Bean으로 분리하여 @Transactional 프록시 정상 동작 보장.
 * SseEventSender.sendEventDirect()로 executor 경합 없이 현재 스레드에서 직접 전송.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseMissedNotificationRecovery {

    private static final int MAX_RECOVERY_SIZE = 50;

    private final NotificationStoragePort storagePort;
    private final SseEventSender sseEventSender;
    private final SseConnectionManager connectionManager;

    @Transactional
    public void recover(Long userId) {
        if (!connectionManager.isUserConnected(userId)) {
            log.debug("SSE 미연결 상태 — 복구 스킵: userId={}", userId);
            return;
        }
        try {
            List<NotificationItemDto> missed = storagePort
                    .findUndeliveredByUserId(userId, MAX_RECOVERY_SIZE);
            if (missed.isEmpty()) return;

            List<Long> sentIds = sendAllDirect(userId, missed);

            if (!sentIds.isEmpty()) {
                storagePort.markDeliveredByIds(sentIds);
            }
            log.debug("놓친 알림 복구: userId={}, sent={}/{}", userId, sentIds.size(), missed.size());
        } catch (Exception e) {
            log.warn("놓친 알림 복구 실패: userId={}", userId, e);
        }
    }

    private List<Long> sendAllDirect(Long userId, List<NotificationItemDto> missed) {
        List<Long> sentIds = new ArrayList<>();
        for (NotificationItemDto item : missed) {
            boolean sent = sseEventSender.sendEventDirect(
                    userId, "notification", NotificationSseDto.from(item));
            if (sent) {
                sentIds.add(item.notificationId());
            }
        }
        return sentIds;
    }
}
