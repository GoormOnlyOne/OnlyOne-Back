package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.sse.service.SseConnectionManager;
import com.example.onlyone.sse.service.SseEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseStreamController {

    private static final int MAX_RECOVERY_SIZE = 50;

    private final SseConnectionManager connectionManager;
    private final AuthService authService;
    private final NotificationRepository notificationRepository;
    private final SseEventSender sseEventSender;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long userId = authService.getCurrentUserId();
        log.info("SSE 연결 요청: userId={}", userId);
        SseEmitter emitter = connectionManager.createConnection(userId);
        sendMissedNotifications(userId);
        return emitter;
    }

    @Transactional
    void sendMissedNotifications(Long userId) {
        try {
            List<NotificationItemDto> missed = notificationRepository
                    .findUnsentNotificationsByUserId(userId, MAX_RECOVERY_SIZE);
            if (missed.isEmpty()) return;

            List<Long> sentIds = new ArrayList<>();
            for (NotificationItemDto item : missed) {
                CompletableFuture<Boolean> result = sseEventSender.sendEvent(userId, "notification", item);
                if (Boolean.TRUE.equals(result.join())) {
                    sentIds.add(item.notificationId());
                }
            }
            if (!sentIds.isEmpty()) {
                notificationRepository.markSseSentByIds(sentIds);
            }
            log.info("놓친 알림 복구: userId={}, sent={}/{}", userId, sentIds.size(), missed.size());
        } catch (Exception e) {
            log.warn("놓친 알림 복구 실패: userId={}", userId, e);
        }
    }
}
