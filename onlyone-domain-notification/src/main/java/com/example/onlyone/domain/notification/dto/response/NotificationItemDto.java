package com.example.onlyone.domain.notification.dto.response;

import com.example.onlyone.domain.notification.entity.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 알림 목록 아이템 DTO
 *
 * 알림 목록에서 각 알림 항목의 정보를 담는 DTO입니다.
 */
public record NotificationItemDto(
    /** 알림 ID */
    @NotNull(message = "알림 ID는 필수입니다")
    @Positive(message = "알림 ID는 양수여야 합니다")
    Long notificationId,

    /** 알림 내용 */
    @NotBlank(message = "알림 내용은 필수입니다")
    String content,

    /** 알림 타입 */
    @NotNull(message = "알림 타입은 필수입니다")
    NotificationType type,

    /** 읽음 여부 */
    @NotNull(message = "읽음 여부는 필수입니다")
    Boolean isRead,

    /** 생성 시간 */
    @NotNull(message = "생성 시간은 필수입니다")
    LocalDateTime createdAt
) {
}
