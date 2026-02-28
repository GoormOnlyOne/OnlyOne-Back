package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.entity.FcmToken;
import com.example.onlyone.domain.notification.repository.FcmTokenRepository;
import com.example.onlyone.domain.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * FCM 디바이스 토큰 등록/해제 API.
 * {@code app.notification.delivery=fcm} 일 때만 활성화된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fcm/tokens")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "fcm")
public class FcmTokenController {

    private final FcmTokenRepository fcmTokenRepository;
    private final AuthService authService;

    @PostMapping
    public ResponseEntity<Void> registerToken(@RequestBody FcmTokenRequest request) {
        Long userId = authService.getCurrentUserId();

        fcmTokenRepository.findByToken(request.token()).ifPresentOrElse(
                existing -> log.debug("이미 등록된 FCM 토큰: userId={}", userId),
                () -> {
                    FcmToken token = FcmToken.builder()
                            .userId(userId)
                            .token(request.token())
                            .deviceType(request.deviceType())
                            .build();
                    fcmTokenRepository.save(token);
                    log.debug("FCM 토큰 등록: userId={}, deviceType={}", userId, request.deviceType());
                }
        );

        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregisterToken(@RequestBody FcmTokenRequest request) {
        fcmTokenRepository.deleteByToken(request.token());
        log.debug("FCM 토큰 해제: token={}...", request.token().substring(0, Math.min(10, request.token().length())));
        return ResponseEntity.ok().build();
    }

    public record FcmTokenRequest(String token, String deviceType) {}
}
