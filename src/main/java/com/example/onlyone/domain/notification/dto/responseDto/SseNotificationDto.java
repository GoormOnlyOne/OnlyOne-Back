package com.example.onlyone.domain.notification.dto.responseDto;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.Type;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * SSE 실시간 알림 전송용 DTO
 *
 * Server-Sent Events를 통해 실시간으로 전송되는 알림 데이터를 담는 DTO입니다.
 * 새로운 알림이 도착했을 때 연결된 클라이언트에게 즉시 전송됩니다.
 *
 * 역할:
 * - SSE 스트림을 통한 실시간 알림 데이터 전송
 *
 * 특징:
 * - 읽음 상태 정보 제외 (새 알림은 항상 읽지 않음 상태)
 * - FCM 상태 정보 제외 (클라이언트에게 불필요)
 *
 * SSE 이벤트 구조:
 * - event: "notification"
 * - data: SseNotificationDto의 JSON 직렬화 데이터
 */
@Getter
@Builder
public class SseNotificationDto {

  private final Long notificationId;

  private final String content;

  private final Type type;

  private LocalDateTime createdAt;

  public static SseNotificationDto from(AppNotification appNotification) {
    java.util.Objects.requireNonNull(appNotification, "알림은 null일 수 없습니다");
    
    return SseNotificationDto.builder()
        .notificationId(appNotification.getId())
        .content(appNotification.getContent())
        .type(appNotification.getNotificationType().getType())
        .createdAt(appNotification.getCreatedAt())
        .build();
  }
}
