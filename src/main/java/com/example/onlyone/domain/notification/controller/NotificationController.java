package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.service.HybridNotificationService;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


@Tag(name = "알림", description = "알림 관리 API")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;
    private final HybridNotificationService hybridNotificationService;
    private final UserService userService;

    @Operation(summary = "읽지 않은 알림 개수", description = "현재 사용자의 읽지 않은 알림 개수를 조회합니다")
    @GetMapping("/unread-count")
    public ResponseEntity<CommonResponse<Long>> getUnreadCount() {
        User currentUser = userService.getCurrentUser();
        Long unreadCount = notificationService.getUnreadCount(currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(unreadCount));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다")
    @PutMapping("/{notificationId}/read")
    public ResponseEntity<CommonResponse<Void>> markAsRead(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUser();
        hybridNotificationService.markNotificationsAsReceived(currentUser.getUserId(), List.of(notificationId));
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "현재 사용자의 모든 알림을 읽음 처리합니다 (비동기)")
    @PutMapping("/read-all")
    public ResponseEntity<CommonResponse<Void>> markAllAsRead() {
        User currentUser = userService.getCurrentUser();
        notificationService.markAllAsRead(currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(null));
    }
    
    @Operation(
        summary = "배치 알림 수신 확인", 
        description = "여러 알림들의 수신을 한번에 확인합니다. 하이브리드 패턴 지원"
    )
    @PostMapping("/acknowledge")
    public ResponseEntity<CommonResponse<Void>> acknowledgeNotifications(
            @Parameter(description = "수신 확인할 알림 ID 목록")
            @RequestBody List<Long> notificationIds) {
        
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        
        hybridNotificationService.markNotificationsAsReceived(userId, notificationIds);
        
        log.info("Notifications acknowledged: userId={}, count={}", 
                userId, notificationIds.size());
        
        return ResponseEntity.ok(CommonResponse.success(null));
    }
    
    @Operation(
        summary = "재연결시 누락 알림 복구", 
        description = "SSE 재연결시 누락된 알림들을 일괄 복구합니다. Last-Event-ID 기반"
    )
    @PostMapping("/recover")
    public ResponseEntity<CommonResponse<Map<String, Object>>> recoverMissedNotifications(
            @Parameter(description = "마지막 수신한 Event ID (SSE Last-Event-ID)")
            @RequestParam String lastEventId) {
        
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        
        // 누락된 알림 전송 (비동기) - Last-Event-ID 기반
        hybridNotificationService.sendMissedNotifications(userId, lastEventId);
        
        // 복구 상태 응답
        Map<String, Object> response = Map.of(
            "status", "recovery_initiated",
            "lastEventId", lastEventId,
            "message", "Missed notifications are being sent via SSE based on Last-Event-ID"
        );
        
        log.info("Notification recovery initiated: userId={}, lastEventId={}", userId, lastEventId);
        
        return ResponseEntity.ok(CommonResponse.success(response));
    }
    
    @Operation(
        summary = "알림 시스템 상태 확인", 
        description = "현재 사용자의 알림 시스템 연결 및 처리 상태를 확인합니다"
    )
    @GetMapping("/status")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getNotificationStatus() {
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        
        // 미처리 알림 개수 조회
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<Notification> pending = hybridNotificationService
            .getPendingNotifications(userId, oneHourAgo, 1);
        
        Map<String, Object> status = Map.of(
            "userId", userId,
            "hasPendingNotifications", !pending.isEmpty(),
            "lastChecked", LocalDateTime.now(),
            "hybridMode", "enabled"
        );
        
        return ResponseEntity.ok(CommonResponse.success(status));
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<CommonResponse<Void>> deleteNotification(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUser();
        notificationService.deleteNotification(currentUser.getUserId(), notificationId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 목록 조회", description = "현재 사용자의 알림 목록을 조회합니다. 시간 기반 필터링 지원")
    @GetMapping
    public ResponseEntity<CommonResponse<List<Notification>>> getNotifications(
        @Parameter(description = "조회 시작 시점 (ISO 형식)")
        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) 
        LocalDateTime since,
        @Parameter(description = "조회할 알림 개수 (최대 100)")
        @RequestParam(defaultValue = "20") int limit) {
        
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        
        // since가 없으면 24시간 전부터 조회
        if (since == null) {
            since = LocalDateTime.now().minusHours(24);
        }
        
        // 최대 100개로 제한
        limit = Math.min(limit, 100);
        
        List<Notification> notifications = hybridNotificationService
            .getPendingNotifications(userId, since, limit);
        
        return ResponseEntity.ok(CommonResponse.success(notifications));
    }
}