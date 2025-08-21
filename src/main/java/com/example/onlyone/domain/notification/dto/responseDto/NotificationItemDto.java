package com.example.onlyone.domain.notification.dto.responseDto;

import com.example.onlyone.domain.notification.entity.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor
public class NotificationItemDto {

  /**
   * 알림 ID
   */
  @NotNull(message = "알림 ID는 필수입니다")
  @Positive(message = "알림 ID는 양수여야 합니다")
  private final Long notificationId;

  /**
   * 알림 내용
   */
  @NotBlank(message = "알림 내용은 필수입니다")
  private final String content;

  /**
   * 알림 타입
   */
  @NotNull(message = "알림 타입은 필수입니다")
  private final Type type;

  /**
   * 읽음 여부
   */
  @NotNull(message = "읽음 여부는 필수입니다")
  private final Boolean isRead;

  /**
   * 생성 시간
   */
  @NotNull(message = "생성 시간은 필수입니다")
  private final LocalDateTime createdAt;

  /**
   * 타겟 타입 (예: CHAT, MATCHING, POST 등)
   */
  @NotBlank(message = "타겟 타입은 필수입니다")
  private final String targetType;

  /**
   * 타겟 ID (채팅방 ID, 매칭 ID, 게시글 ID 등)
   */
  @Positive(message = "타겟 ID는 양수여야 합니다")
  private final Long targetId;
}