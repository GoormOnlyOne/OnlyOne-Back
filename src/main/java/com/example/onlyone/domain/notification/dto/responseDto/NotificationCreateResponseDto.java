package com.example.onlyone.domain.notification.dto.responseDto;

import com.example.onlyone.domain.notification.entity.Notification;
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

  private final Long notificationId;

  private final String content;

  private final Boolean fcmSent;

  private LocalDateTime createdAt;

  public static NotificationCreateResponseDto from(Notification notification) {
    return NotificationCreateResponseDto.builder()
        .notificationId(notification.getNotificationId())
        .content(notification.getContent())
        .fcmSent(notification.getFcmSent())
        .createdAt(notification.getCreatedAt())
        .build();
  }
}