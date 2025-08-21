package com.example.onlyone.domain.notification.dto.responseDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 알림 목록 조회 응답 DTO
 *
 * 알림 목록 조회 API의 응답 데이터를 담는 DTO입니다.
 * 커서 기반 페이징 정보와 읽지 않은 개수 정보를 포함합니다.
 */
@Getter
@Builder
public class NotificationListResponseDto {

  @NotNull(message = "알림 목록은 필수입니다")
  @Valid
  private final List<NotificationItemDto> notifications;

  @PositiveOrZero(message = "커서는 0 이상이어야 합니다")
  private final Long cursor;

  @NotNull(message = "다음 페이지 존재 여부는 필수입니다")
  private final boolean hasMore;

  @NotNull(message = "읽지 않은 개수는 필수입니다")
  @PositiveOrZero(message = "읽지 않은 개수는 0 이상이어야 합니다")
  private final Long unreadCount;
}
