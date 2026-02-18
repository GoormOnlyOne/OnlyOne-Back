package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "notification", indexes = {
    @Index(name = "idx_notification_user_id_desc", columnList = "user_id, notification_id DESC"),
    @Index(name = "idx_notification_user_read", columnList = "user_id, is_read"),
    @Index(name = "idx_notification_user_sse_sent", columnList = "user_id, sse_sent")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false, nullable = false)
    private User user;

    @Column(name = "sse_sent", nullable = false)
    private boolean sseSent = false;

    private Notification(User user, NotificationType type, String content) {
        if (user == null || type == null || content == null) {
            throw new IllegalArgumentException("User, Type, and content cannot be null");
        }
        this.user = user;
        this.type = type;
        this.content = content;
    }

    public static Notification create(User user, NotificationType type, String... args) {
        String renderedContent = type.render(args);
        return new Notification(user, type, renderedContent);
    }

    public void markAsRead() {
        this.isRead = true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Notification that)) return false;

        if (id == null && that.id == null) {
            return false;
        }
        
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hash(id) : getClass().hashCode();
    }

    @Override
    public String toString() {
        return String.format("Notification{id=%s, content='%s', isRead=%s}", 
                id, content, isRead);
    }
}