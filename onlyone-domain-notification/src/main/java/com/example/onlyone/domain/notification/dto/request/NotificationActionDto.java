package com.example.onlyone.domain.notification.dto.request;

/**
 * 알림 액션 요청 DTO (읽음, 삭제)
 * userId는 Spring Security에서 자동 추출
 */
public record NotificationActionDto(
        Long notificationId
) {
}
