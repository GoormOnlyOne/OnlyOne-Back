package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 알림 컨트롤러 - 개선된 버전
 */
@Tag(name = "알림", description = "실시간 알림 및 알림 관리 API")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

  private static final int MAX_PAGE_SIZE = 100;
  private static final int DEFAULT_PAGE_SIZE = 20;

  private final NotificationService notificationService;
  private final UserService userService;

  /**
   * 알림 생성
   */
  @Operation(summary = "알림 생성", description = "새로운 알림을 생성합니다")
  @PostMapping
  public ResponseEntity<CommonResponse<NotificationCreateResponseDto>> createNotification(
      @Valid @RequestBody NotificationCreateRequestDto requestDto) {

    log.info("Notification creation requested: userId={}, type={}", requestDto.getUserId(), requestDto.getType());

    NotificationCreateResponseDto responseDto = notificationService.createNotification(requestDto);
    return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(responseDto));
  }

  /**
   * 알림 목록 조회 (커서 기반 페이징)
   */
  @Operation(summary = "알림 목록 조회", description = "커서 기반 페이징으로 모든 알림 목록을 조회합니다 (읽음/읽지않음 포함). 첫 페이지 조회 시 자동으로 모든 알림을 읽음 처리합니다")
  @GetMapping
  public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "20") int size) {

    User currentUser = userService.getCurrentUser();
    Long userId = currentUser.getUserId();
    
    size = validatePageSize(size);
    
    NotificationListResponseDto dto = notificationService.getNotifications(userId, cursor, size);
    
    // 첫 페이지 조회 시 자동 읽음 처리
    if (cursor == null) {
      log.info("Auto marking all notifications as read for user: {}", userId);
      notificationService.markAllAsRead(userId);
    }
    
    log.info("Notifications fetched for user: {}, count: {}, unreadCount: {}", 
        userId, dto.getNotifications().size(), dto.getUnreadCount());
    
    return ResponseEntity.ok(CommonResponse.success(dto));
  }

  /**
   * 읽지 않은 알림 개수 조회
   */
  @Operation(summary = "읽지 않은 알림 개수 조회", description = "현재 인증된 사용자의 읽지 않은 알림 개수를 조회합니다")
  @GetMapping("/unread-count")
  public ResponseEntity<CommonResponse<Long>> getUnreadCount() {
    
    User currentUser = userService.getCurrentUser();
    Long userId = currentUser.getUserId();
    
    Long unreadCount = notificationService.getUnreadCount(userId);
    
    log.info("Unread count fetched for user: {}, count: {}", userId, unreadCount);
    
    return ResponseEntity.ok(CommonResponse.success(unreadCount));
  }

  /**
   * 알림 삭제
   */
  @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
  @DeleteMapping("/{notificationId}")
  public ResponseEntity<Void> deleteNotification(@PathVariable Long notificationId) {

    User currentUser = userService.getCurrentUser();
    Long userId = currentUser.getUserId();
    
    notificationService.deleteNotification(userId, notificationId);
    return ResponseEntity.noContent().build();
  }



  // ================================
  // Private Helper Methods
  // ================================

  private int validatePageSize(int size) {
    if (size > MAX_PAGE_SIZE) {
      log.warn("Page size exceeded maximum: requested={}, using={}", size, MAX_PAGE_SIZE);
      return MAX_PAGE_SIZE;
    }
    return size;
  }
}