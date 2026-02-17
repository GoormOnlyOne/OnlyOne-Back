package com.example.onlyone.controller;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 배치 성능 테스트용 엔드포인트 (local 프로필에서만 활성화)
 */
@Profile("local")
@RestController
@RequestMapping("/test/notifications")
@RequiredArgsConstructor
public class NotificationTestController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    /**
     * 테스트 알림 생성 - 현재 인증된 사용자에게 알림 생성
     * SSE 배치 파이프라인 성능 측정용
     */
    @PostMapping("/create")
    public ResponseEntity<CommonResponse<String>> createTestNotification(
            @RequestParam(defaultValue = "LIKE") String type,
            @RequestParam(defaultValue = "1") int count) {

        Long userId = notificationService.getCurrentUserId();
        User user = userRepository.findById(userId).orElseThrow();
        NotificationType notificationType = NotificationType.valueOf(type);
        int created = Math.min(count, 50); // 1회 최대 50개

        for (int i = 0; i < created; i++) {
            notificationService.createNotification(user, notificationType, "loadtest-user");
        }

        return ResponseEntity.ok(CommonResponse.success("created " + created));
    }
}
