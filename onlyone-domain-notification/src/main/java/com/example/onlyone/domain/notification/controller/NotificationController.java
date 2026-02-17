package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.request.NotificationActionDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.common.CommonResponse;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 컨트롤러
 * 모든 메서드는 Spring Security를 통해 자동 인증되며, DTO 기반으로 동작
 */
@Tag(name = "알림", description = "알림 관리 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "읽지 않은 알림 개수", description = "현재 사용자의 읽지 않은 알림 개수를 조회합니다")
    @GetMapping("/unread-count")
    public ResponseEntity<CommonResponse<Long>> getUnreadCount() {
        Long unreadCount = notificationService.getUnreadCount();
        return ResponseEntity.ok(CommonResponse.success(unreadCount));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다")
    @PutMapping("/{notificationId}/read")
    public ResponseEntity<CommonResponse<Void>> markAsRead(@PathVariable Long notificationId) {
        NotificationActionDto dto = new NotificationActionDto(notificationId);
        notificationService.markAsRead(dto);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "현재 사용자의 모든 알림을 읽음 처리합니다")
    @PutMapping("/read-all")
    public ResponseEntity<CommonResponse<Void>> markAllAsRead() {
        notificationService.markAllAsRead();
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<CommonResponse<Void>> deleteNotification(@PathVariable Long notificationId) {
        NotificationActionDto dto = new NotificationActionDto(notificationId);
        notificationService.deleteNotification(dto);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 목록 조회", description = "현재 사용자의 알림 목록을 페이징하여 조회합니다")
    @GetMapping
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
            @Parameter(description = "커서 (이전 조회의 마지막 알림 ID)")
            @RequestParam(required = false) Long cursor,
            @Parameter(description = "페이지 크기 (최대 30)")
            @RequestParam(defaultValue = "20") int size) {

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        NotificationQueryDto dto = new NotificationQueryDto(cursor, size);
        NotificationListResponseDto notifications = notificationService.getNotifications(dto);

        stopWatch.stop();
        log.debug("알림 목록 조회 완료: {}ms, size={}, results={}",
                stopWatch.getTotalTimeMillis(), size, notifications.notifications().size());

        return ResponseEntity.ok(CommonResponse.success(notifications));
    }

    @Operation(summary = "배치 처리 상태", description = "알림 배치 처리 큐 상태를 조회합니다 (모니터링용)")
    @GetMapping("/batch-status")
    public ResponseEntity<CommonResponse<Object>> getBatchStatus() {
        return ResponseEntity.ok(CommonResponse.success(notificationService.getBatchStatus()));
    }
}