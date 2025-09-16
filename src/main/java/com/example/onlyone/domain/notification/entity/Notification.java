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
    @Index(name = "idx_notification_sse_failed", columnList = "user_id, sse_sent"),
    @Index(name = "idx_notification_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

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

    @Column(name = "sse_sent", nullable = false)
    private boolean sseSent = false;


    private Notification(User user, NotificationType notificationType, String content) {
        if (user == null || notificationType == null || content == null) {
            throw new IllegalArgumentException("User, NotificationType, and content cannot be null");
        }
        this.user = user;
        this.notificationType = notificationType;
        this.content = content;
    }

    public static Notification create(User user, NotificationType notificationType, 
                                         String... args) {
        String renderedContent = notificationType.render(args);
        return new Notification(user, notificationType, renderedContent);
    }

    public String getTargetType() {
        return notificationType.getType().getTargetType();
    }

    public void markAsRead() {
        this.isRead = true;
    }

    public void markSseSent() {
        this.sseSent = true;
    }
    
    public boolean isSseSent() {
        return sseSent;
    }




    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Notification that)) return false;
        
        // 두 엔티티 모두 id가 null인 경우 (아직 영속화되지 않은 경우)
        if (id == null && that.id == null) {
            return false; // 서로 다른 transient 객체로 간주
        }
        
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        // id가 null인 경우에도 일관된 hashCode 반환
        return id != null ? Objects.hash(id) : getClass().hashCode();
    }

    @Override
    public String toString() {
        return String.format("Notification{id=%s, content='%s', isRead=%s}", 
                id, content, isRead);
    }
}