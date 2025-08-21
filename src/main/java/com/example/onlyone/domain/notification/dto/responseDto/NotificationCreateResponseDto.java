package com.example.onlyone.domain.notification.dto.responseDto;

import com.example.onlyone.domain.notification.entity.AppNotification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 알림 생성 응답 DTO
 *
 * 알림 생성 완료 후 클라이언트에게 반환되는 정보를 담는 DTO입니다.
 * 생성된 알림의 기본 정보와 전송 상태를 포함합니다.
 */
@Getter
@Builder
public class NotificationCreateResponseDto {

  @NotNull(message = "알림 ID는 필수입니다")
  @Positive(message = "알림 ID는 양수여야 합니다")
  private final Long notificationId;

  @NotBlank(message = "알림 내용은 필수입니다")
  private final String content;

  @NotNull(message = "FCM 전송 상태는 필수입니다")
  private final Boolean fcmSent;

  @NotNull(message = "생성 시간은 필수입니다")
  private LocalDateTime createdAt;

  public static NotificationCreateResponseDto from(AppNotification appNotification) {
    return NotificationCreateResponseDto.builder()
        .notificationId(appNotification.getId())
        .content(appNotification.getContent())
        .fcmSent(appNotification.isFcmSent())
        .createdAt(appNotification.getCreatedAt())
        .build();
  }
}