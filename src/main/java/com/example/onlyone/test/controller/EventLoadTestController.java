package com.example.onlyone.test.controller;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * 이벤트 퍼블리셔 부하테스트
 */
@RestController
@RequestMapping("/test/events")
@RequiredArgsConstructor
@Slf4j
public class EventLoadTestController {

    private final ApplicationEventPublisher eventPublisher;
    private final Random random = new Random();

    @PostMapping("/load/{count}")
    public CommonResponse<String> publishEvents(@PathVariable int count) {
        
        log.info("이벤트 부하테스트 시작: {}개", count);
        long startTime = System.currentTimeMillis();
        
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < count; i++) {
                Notification mockNotification = createRandomNotification((long)(i % 10 + 1), i);
                NotificationCreatedEvent event = new NotificationCreatedEvent(mockNotification);
                eventPublisher.publishEvent(event);
            }
        });
        
        long duration = System.currentTimeMillis() - startTime;
        String result = String.format("이벤트 %d개 발행 시작: %dms", count, duration);
        
        return CommonResponse.success(result);
    }

    private Notification createRandomNotification(Long userId, int index) {
        // 목 사용자 - Builder 패턴 사용
        User mockUser = User.builder()
                .userId(userId)
                .kakaoId(1000000L + userId)
                .nickname("테스터" + userId)
                .status(com.example.onlyone.domain.user.entity.Status.ACTIVE)
                .build();
        
        // 랜덤 알림 타입 선택
        Type[] types = {Type.CHAT, Type.SETTLEMENT, Type.LIKE, Type.COMMENT, Type.REFEED};
        Type randomType = types[random.nextInt(types.length)];
        
        // 목 알림 타입 - of 메서드 사용
        String template = switch (randomType) {
            case CHAT -> "%s님이 메시지를 보냈습니다.";
            case SETTLEMENT -> "정산이 완료되었습니다. 금액: %s원";
            case LIKE -> "%s님이 좋아요를 눌렀습니다.";
            case COMMENT -> "%s님이 댓글을 남겼습니다.";
            case REFEED -> "%s님이 회원님의 피드를 리피드했습니다.";
        };
        
        NotificationType mockType = NotificationType.of(randomType, template);
        
        String args = switch (randomType) {
            case CHAT -> "테스터" + (index % 100);
            case SETTLEMENT -> "10000";
            case LIKE -> "테스터" + (index % 100);
            case COMMENT -> "테스터" + (index % 100);
            case REFEED -> "테스터" + (index % 100);
        };
        
        return Notification.create(mockUser, mockType, args);
    }
}