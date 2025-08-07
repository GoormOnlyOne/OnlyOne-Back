package com.example.onlyone.domain.notification.dto.responseDto;

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

  private final List<NotificationItemDto> notifications;

  private final Long cursor;

  private final boolean hasMore;

  private final Long unreadCount;
}
