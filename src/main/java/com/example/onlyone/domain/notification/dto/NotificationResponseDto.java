    package com.example.onlyone.domain.notification.dto;

    import com.example.onlyone.domain.notification.entity.Notification;
    import com.fasterxml.jackson.annotation.JsonProperty;
    import lombok.Builder;
    import lombok.Getter;

    import java.time.LocalDateTime;

    @Getter
    @Builder
    public class NotificationResponseDto {

        private final Long notificationId;

        private final String content;

        @JsonProperty("isRead")
        private final boolean isRead;

        private final boolean fcmSent;

        private final LocalDateTime createdAt;

        public static NotificationResponseDto fromEntity(Notification n) {
            return NotificationResponseDto.builder()
                    .notificationId(n.getNotificationId())
                    .content(n.getContent())
                    .isRead(n.getIsRead())
                    .fcmSent(n.getFcmSent())
                    .createdAt(n.getCreatedAt())
                    .build();
        }
    }