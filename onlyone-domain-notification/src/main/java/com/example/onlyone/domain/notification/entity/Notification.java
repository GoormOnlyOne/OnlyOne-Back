package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "notification", indexes = {
    // 커서 기반 페이징 최적화 (ORDER BY notification_id DESC, filesort 제거)
    @Index(name = "idx_notification_user_id_desc", columnList = "user_id, notification_id DESC"),

    // 메인 알림 조회 최적화 (user_id로 조회 후 created_at DESC, notification_id DESC 정렬)
    @Index(name = "idx_notification_user_created", columnList = "user_id, created_at DESC, notification_id DESC"),

    // 읽지 않은 알림 개수 및 상세 조회용 (COUNT 및 필터링 최적화)
    @Index(name = "idx_notification_user_read", columnList = "user_id, is_read"),

    // 타입별 알림 조회 최적화 (user_id + type + 시간순 정렬)
    @Index(name = "idx_notification_user_type_created", columnList = "user_id, type, created_at DESC"),

    // SSE 전송 실패 재시도용 (미전송 알림 조회)
    @Index(name = "idx_notification_sse_failed", columnList = "user_id, sse_sent"),

    // SSE 전송용 (미전송 + 사용자별 + 생성시간 순)
    @Index(name = "idx_notification_sse_unsent", columnList = "sse_sent, user_id, created_at ASC"),

    // 전체 알림 관리용 (관리자 페이지 등)
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

    public String getTargetType() {
        return type.getTargetType();
    }

    public void markAsRead() {
        this.isRead = true;
    }

    public void markSseSent() {
        this.sseSent = true;
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