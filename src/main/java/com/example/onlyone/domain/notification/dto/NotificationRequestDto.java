package com.example.onlyone.domain.notification.dto;

import com.example.onlyone.domain.notification.entity.Type;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationRequestDto {

    @NotNull
    private final Long userId;

    @NotNull
    private final Type type;

    @NotNull
    private final String[] args;

}
