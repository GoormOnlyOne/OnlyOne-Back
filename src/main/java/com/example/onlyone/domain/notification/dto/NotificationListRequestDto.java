package com.example.onlyone.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationListRequestDto {

    private final Long userId;
    private final Boolean isRead;

}
