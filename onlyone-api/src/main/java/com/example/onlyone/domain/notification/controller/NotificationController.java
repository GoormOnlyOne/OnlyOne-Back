package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.service.NotificationCommandService;
import com.example.onlyone.domain.notification.service.NotificationQueryService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@Tag(name = "알림", description = "알림 관리 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService queryService;
    private final NotificationCommandService commandService;

    @Operation(summary = "알림 목록 조회", description = "현재 사용자의 알림 목록을 페이징하여 조회합니다")
    @GetMapping
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
            @Parameter(description = "커서 (이전 조회의 마지막 알림 ID)")
            @RequestParam(required = false) Long cursor,
            @Parameter(description = "페이지 크기 (최대 30)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(30) int size) {

        NotificationQueryDto dto = new NotificationQueryDto(cursor, size);
        NotificationListResponseDto notifications = queryService.getNotifications(dto);
        return ResponseEntity.ok(CommonResponse.success(notifications));
    }

    @Operation(summary = "읽지 않은 알림 개수", description = "현재 사용자의 읽지 않은 알림 개수를 조회합니다")
    @GetMapping("/unread-count")
    public ResponseEntity<CommonResponse<Long>> getUnreadCount() {
        return ResponseEntity.ok(CommonResponse.success(queryService.getUnreadCount()));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다")
    @PutMapping("/{notificationId}/read")
    public ResponseEntity<CommonResponse<Void>> markAsRead(@PathVariable Long notificationId) {
        commandService.markAsRead(notificationId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "현재 사용자의 모든 알림을 읽음 처리합니다")
    @PutMapping("/read-all")
    public ResponseEntity<CommonResponse<Void>> markAllAsRead() {
        commandService.markAllAsRead();
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<CommonResponse<Void>> deleteNotification(@PathVariable Long notificationId) {
        commandService.deleteNotification(notificationId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

}
