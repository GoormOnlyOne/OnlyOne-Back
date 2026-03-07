package com.example.onlyone.domain.notification.dto.request;

/**
 * 알림 목록 조회 요청 DTO
 * userId는 Spring Security에서 자동 추출
 */
public record NotificationQueryDto(
        Long cursor,
        int size
) {
    public NotificationQueryDto {
        if (size <= 0) {
            size = 20;
        }
    }
}
