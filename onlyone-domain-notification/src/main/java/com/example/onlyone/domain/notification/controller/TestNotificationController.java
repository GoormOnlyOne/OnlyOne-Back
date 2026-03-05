package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.port.NotificationEventPublisher;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import com.example.onlyone.domain.notification.service.NotificationUnreadCounter;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * k6 부하 테스트용 알림 생성 API.
 * 인증 없이 알림을 생성하여 delivery latency를 측정할 수 있다.
 * local/test 프로필에서만 활성화된다.
 */
@Profile({"local", "test", "ec2"})
@RestController
@RequestMapping("/test/notifications")
@RequiredArgsConstructor
public class TestNotificationController {

    private final NotificationStoragePort storagePort;
    private final NotificationEventPublisher eventPublisher;
    private final NotificationUnreadCounter unreadCounter;
    private final TransactionTemplate transactionTemplate;

    @PostMapping("/create")
    public ResponseEntity<CommonResponse<Map<String, Object>>> create(
            @RequestBody CreateRequest request) {

        NotificationType type = NotificationType.valueOf(request.type());
        String content = type.render("k6-test-user");

        // 트랜잭션 내에서 save + publish → AFTER_COMMIT 리스너 정상 트리거
        Long notificationId = transactionTemplate.execute(status -> {
            Long id = storagePort.save(request.targetUserId(), type, content);
            unreadCounter.increment(request.targetUserId());
            eventPublisher.publish(new NotificationCreatedEvent(
                    id, request.targetUserId(), content, type, false, LocalDateTime.now()));
            return id;
        });

        return ResponseEntity.ok(CommonResponse.success(Map.of(
                "notificationId", notificationId,
                "serverTimestamp", Instant.now().toEpochMilli()
        )));
    }

    record CreateRequest(Long targetUserId, String type) {}
}
