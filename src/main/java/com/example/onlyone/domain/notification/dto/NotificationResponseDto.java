    package com.example.onlyone.domain.notification.dto;

    import com.example.onlyone.domain.notification.entity.Notification;
    import com.fasterxml.jackson.annotation.JsonProperty;
    import lombok.Builder;
    import lombok.Getter;

    import java.time.LocalDateTime;

    @Getter
    @Builder
    public class NotificationResponseDto {

        @JsonProperty("notification_id")
        private final Long notificationId;

        private final String content;

        @JsonProperty("fcm_sent")
        private final boolean fcmSent;

        @JsonProperty("created_at")
        private final LocalDateTime createdAt;

        public NotificationResponseDto(Long notificationId,
                                  String content,
                                  boolean fcmSent,
                                  LocalDateTime createdAt) {
            this.notificationId = notificationId;
            this.content        = content;
            this.fcmSent        = fcmSent;
            this.createdAt      = createdAt;
        }

        public static NotificationResponseDto fromEntity(Notification n) {
            return new NotificationResponseDto(
                    n.getNotificationId(),
                    n.getContent(),
                    n.getFcmSent(),
                    n.getCreatedAt()
            );
        }
    }