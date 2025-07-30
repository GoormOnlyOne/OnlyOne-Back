package com.example.onlyone.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationListRequestDto {

    private final long userId;
    private final long isRead;

}
