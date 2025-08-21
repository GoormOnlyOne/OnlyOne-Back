package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
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

import jakarta.validation.Valid;

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
        User currentUser = userService.getCurrentUser();
        Long unreadCount = notificationService.getUnreadCount(currentUser.getUserId());
        return ResponseEntity.ok(CommonResponse.success(unreadCount));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다")
    @PutMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUser();
        notificationService.markAsRead(notificationId, currentUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "현재 사용자의 모든 알림을 읽음 처리합니다")
    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead() {
        User currentUser = userService.getCurrentUser();
        notificationService.markAllAsRead(currentUser.getUserId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long notificationId) {
        User currentUser = userService.getCurrentUser();
        notificationService.deleteNotification(currentUser.getUserId(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "알림 목록 조회", description = "현재 사용자의 알림 목록을 페이징하여 조회합니다")
    @GetMapping
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
        @Parameter(description = "커서 (이전 조회의 마지막 알림 ID)")
        @RequestParam(required = false) Long cursor,
        @Parameter(description = "페이지 크기 (최대 100)")
        @RequestParam(defaultValue = "20") int size) {
        
        User currentUser = userService.getCurrentUser();
        NotificationListResponseDto response = notificationService.getNotifications(
            currentUser.getUserId(), cursor, size);
        
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "타입별 알림 조회", description = "특정 타입의 알림만 필터링하여 조회합니다")
    @GetMapping("/type/{type}")
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotificationsByType(
        @Parameter(description = "알림 타입")
        @PathVariable Type type,
        @Parameter(description = "커서 (이전 조회의 마지막 알림 ID)")
        @RequestParam(required = false) Long cursor,
        @Parameter(description = "페이지 크기 (최대 100)")
        @RequestParam(defaultValue = "20") int size) {
        
        User currentUser = userService.getCurrentUser();
        NotificationListResponseDto response = notificationService.getNotificationsByType(
            currentUser.getUserId(), type, cursor, size);
        
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "알림 생성", description = "새로운 알림을 생성합니다 (테스트/관리용)")
    @PostMapping
    public ResponseEntity<CommonResponse<NotificationCreateResponseDto>> createNotification(
        @Parameter(description = "알림 생성 요청 정보")
        @Valid @RequestBody NotificationCreateRequestDto requestDto) {
        
        NotificationCreateResponseDto response = notificationService.createNotification(requestDto);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}