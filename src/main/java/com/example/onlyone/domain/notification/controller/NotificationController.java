package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;



@Tag(name = "알림", description = "알림 관리 API")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    @Operation(summary = "읽지 않은 알림 개수", description = "현재 사용자의 읽지 않은 알림 개수를 조회합니다")
    @GetMapping("/unread-count")
    public ResponseEntity<CommonResponse<Long>> getUnreadCount() {
        // JWT 기반 사용자 정보 사용 (DB 조회 없음)
        User currentUser = userService.getCurrentUserFromJwt();
        Long unreadCount = notificationService.getUnreadCount(currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(unreadCount));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다")
    @PutMapping("/{notificationId}/read")
    public ResponseEntity<CommonResponse<Void>> markAsRead(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUserFromJwt();
        notificationService.markAsRead(notificationId, currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "현재 사용자의 모든 알림을 읽음 처리합니다 (비동기)")
    @PutMapping("/read-all")
    public ResponseEntity<CommonResponse<Void>> markAllAsRead() {
        User currentUser = userService.getCurrentUserFromJwt();
        notificationService.markAllAsRead(currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<CommonResponse<Void>> deleteNotification(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUserFromJwt();
        notificationService.deleteNotification(currentUser.getUserId(), notificationId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 목록 조회", description = "현재 사용자의 알림 목록을 조회합니다")
    @GetMapping
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
        @Parameter(description = "커서 (페이지네이션)")
        @RequestParam(required = false) Long cursor,
        @Parameter(description = "조회할 알림 개수 (최대 30)")
        @RequestParam(defaultValue = "20") int size) {
        
        User currentUser = userService.getCurrentUserFromJwt();
        Long userId = currentUser.getUserId();
        
        // 최대 30개로 제한
        size = Math.min(size, 30);
        
        NotificationListResponseDto notifications = notificationService.getNotifications(userId, cursor, size);
        
        return ResponseEntity.ok(CommonResponse.success(notifications));
    }
}