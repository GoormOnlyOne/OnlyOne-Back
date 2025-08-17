package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "notification")
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
    private boolean isRead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", nullable = false)
    private NotificationType notificationType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false, nullable = false)
    private User user;

    @Column(name = "fcm_sent", nullable = false)
    private boolean fcmSent;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    private AppNotification(User user, NotificationType notificationType, String content) {
        this.user = Objects.requireNonNull(user, "user cannot be null");
        this.notificationType = Objects.requireNonNull(notificationType, "notificationType cannot be null");
        this.content = Objects.requireNonNull(content, "content cannot be null");
        this.isRead = false;
        this.fcmSent = false;
        this.targetType = null;
        this.targetId = null;
    }

    private AppNotification(User user, NotificationType notificationType, String content, String targetType, Long targetId) {
        this(user, notificationType, content);
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public static AppNotification create(User user, NotificationType notificationType, String... args) {
        String renderedContent = notificationType.render(args);
        return new AppNotification(user, notificationType, renderedContent);
    }

    public static AppNotification createWithTarget(User user, NotificationType notificationType, 
                                                   String targetType, Long targetId, String... args) {
        String renderedContent = notificationType.render(args);
        return new AppNotification(user, notificationType, renderedContent, targetType, targetId);
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