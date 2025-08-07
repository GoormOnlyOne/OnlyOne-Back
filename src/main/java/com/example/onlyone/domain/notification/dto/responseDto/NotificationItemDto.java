package com.example.onlyone.domain.notification.dto.responseDto;

import com.example.onlyone.domain.notification.entity.Type;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 알림 목록 아이템 DTO
 *
 * 알림 목록에서 각 알림 항목의 정보를 담는 DTO입니다.
 */
@Getter
@Builder
public class NotificationItemDto {

  /**
   * 알림 ID
   */
  private final Long notificationId;

  /**
   * 알림 내용
   */
  private final String content;

  /**
   * 알림 타입
   */
  private final Type type;

  /**
   * 읽음 여부
   */
  private final Boolean isRead;

  /**
   * 생성 시간
   */
  private final LocalDateTime createdAt;
}