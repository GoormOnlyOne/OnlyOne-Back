package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id", updatable = false)
    private Long notificationId;

    @Column(name = "content")
    @NotNull
    private String content;

    @Column(name = "is_read")
    @NotNull
    private Boolean isRead;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "type_id")
    @NotNull
    private NotificationType notificationType;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", updatable = false)
    @NotNull
    private User user;

    @Column(name = "fcm_sent", nullable = false)
    private Boolean fcmSent = false;

    // create 정적 팩토리 메서드, 알림이 만들어질 때(메세지 내용, 타입, 받는 사람)
    public static Notification create(User user, NotificationType notificationType, String... templateArgs) {
        Notification n = new Notification();
        n.user = user;
        n.notificationType = notificationType;
        n.content = notificationType.render(templateArgs);
        n.isRead = false;
        return n;
    }

    // 알림 읽음 처리
    public void markAsRead() {
        this.isRead = true;
    }

    // fcm 전송 실패 여부
    public void markFcmSent(boolean sent) {
        this.fcmSent = sent;
    }

}