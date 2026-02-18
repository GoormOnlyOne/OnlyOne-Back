package com.example.onlyone.domain.notification.fixture;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

public final class NotificationFixtures {

    private NotificationFixtures() {}

    // ==================== User ====================

    public static final Long DEFAULT_USER_ID = 1L;

    public static User user() {
        return user(DEFAULT_USER_ID, "테스트유저");
    }

    public static User user(Long userId, String nickname) {
        return User.builder()
                .userId(userId)
                .kakaoId(userId * 1000)
                .nickname(nickname)
                .status(Status.ACTIVE)
                .build();
    }

    public static User otherUser() {
        return user(2L, "다른유저");
    }

    // ==================== Notification ====================

    public static Notification notification(User user, NotificationType type, String... args) {
        return Notification.create(user, type, args);
    }

    public static Notification notification(Long id, User user, NotificationType type, String... args) {
        Notification notification = Notification.create(user, type, args);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    public static Notification likeNotification(User user) {
        return notification(user, NotificationType.LIKE, "홍길동");
    }

    public static Notification likeNotification(Long id, User user) {
        return notification(id, user, NotificationType.LIKE, "홍길동");
    }

    // ==================== DTO ====================

    public static NotificationItemDto notificationItem(Long id) {
        return new NotificationItemDto(
                id,
                "알림 내용 " + id,
                NotificationType.LIKE,
                false,
                LocalDateTime.now()
        );
    }
}
