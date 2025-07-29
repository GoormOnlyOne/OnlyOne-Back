package com.example.onlyone.domain.notification.dto;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.user.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class NotificationRequestDto {

    @NotNull
    @JsonProperty("user_id")
    private Long userId;

    @NotBlank
    @JsonProperty("type_code")
    private String typeCode;

    @NotBlank
    private String content;

    public Notification toEntity(User user, NotificationType notificationType) {
        return Notification.create(user, notificationType, this.content);
    }
}
