package com.example.onlyone.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class NotificationListResponseDto {

  private final List<NotificationItemDto> notifications;

  private final Long cursor;

  private final boolean hasMore;

  private final Long unreadCount;
}
