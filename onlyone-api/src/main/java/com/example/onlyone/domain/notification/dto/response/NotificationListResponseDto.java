package com.example.onlyone.domain.notification.dto.response;

import java.util.List;

public record NotificationListResponseDto(
    List<NotificationItemDto> notifications,
    Long cursor,
    boolean hasMore
) {
}
