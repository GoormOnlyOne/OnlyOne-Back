package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "notification", indexes = {
    @Index(name = "idx_notification_user_created", columnList = "user_id, created_at DESC, notification_id DESC"),
    @Index(name = "idx_notification_user_read", columnList = "user_id, is_read"),
    @Index(name = "idx_notification_user_type_created", columnList = "user_id, type_id, created_at DESC"),
    @Index(name = "idx_notification_fcm_failed", columnList = "user_id, fcm_sent, type_id"),
    @Index(name = "idx_notification_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppNotification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id", updatable = false)
    private Long id;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", nullable = false)
    private NotificationType notificationType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false, nullable = false)
    private User user;

    @Column(name = "fcm_sent", nullable = false)
    private boolean fcmSent = false;

    private AppNotification(User user, NotificationType notificationType, String content) {
        this.user = user;
        this.notificationType = notificationType;
        this.content = content;
//        this.isRead = false;
//        this.fcmSent = false;
    }

    public static AppNotification create(User user, NotificationType notificationType, 
                                         String... args) {
        String renderedContent = notificationType.render(args);
        return new AppNotification(user, notificationType, renderedContent);
    }

    public String getTargetType() {
        return notificationType.getType().getTargetType();
    }

    public void markAsRead() {
        this.isRead = true;
    }

    public void markFcmSent() {
        this.fcmSent = true;
    }

    public boolean shouldSendFcm() {
        return notificationType.getDeliveryMethod().shouldSendFcm();
    }

    public boolean shouldSendSse() {
        return notificationType.getDeliveryMethod().shouldSendSse();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AppNotification that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("AppNotification{id=%d, content='%s', isRead=%s}", 
                id, content, isRead);
    }
}