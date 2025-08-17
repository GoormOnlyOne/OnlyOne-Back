package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * AppNotification 엔티티 클래식 단위 테스트
 * - Mock 사용하지 않음
 * - 순수 도메인 로직만 테스트
 * - 실제 엔티티 메서드만 사용
 */
@DisplayName("AppNotification 클래식 테스트")
class AppNotificationClassicalTest {

    private User testUser;
    private NotificationType chatType;
    private NotificationType likeType;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
            .userId(1L)
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .fcmToken("test_token")
            .build();

        chatType = NotificationType.of(Type.CHAT, "%s님이 메시지를 보냈습니다.");
        likeType = NotificationType.of(Type.LIKE, "%s님이 게시글을 좋아합니다.");
    }

    @Test
    @DisplayName("기본 알림을 생성한다")
    void creates_basic_notification() {
        // when
        AppNotification notification = AppNotification.create(testUser, chatType, "홍길동");

        // then
        assertThat(notification.getUser()).isEqualTo(testUser);
        assertThat(notification.getNotificationType()).isEqualTo(chatType);
        assertThat(notification.getContent()).isEqualTo("홍길동님이 메시지를 보냈습니다.");
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.isFcmSent()).isFalse();
    }

    @Test
    @DisplayName("타겟 정보와 함께 알림을 생성한다")
    void creates_notification_with_target() {
        // when
        AppNotification notification = AppNotification.createWithTarget(
            testUser, chatType, "CHAT", 123L, "이영희");

        // then
        assertThat(notification.getTargetType()).isEqualTo("CHAT");
        assertThat(notification.getTargetId()).isEqualTo(123L);
        assertThat(notification.getContent()).isEqualTo("이영희님이 메시지를 보냈습니다.");
    }

    @Test
    @DisplayName("알림을 읽음 처리한다")
    void marks_notification_as_read() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "발신자");
        assertThat(notification.isRead()).isFalse();

        // when
        notification.markAsRead();

        // then
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("FCM 전송을 완료 처리한다")
    void marks_fcm_as_sent() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "발신자");
        assertThat(notification.isFcmSent()).isFalse();

        // when
        notification.markFcmSent();

        // then
        assertThat(notification.isFcmSent()).isTrue();
    }

    @Test
    @DisplayName("알림 타입에 따라 FCM 전송 여부를 결정한다")
    void determines_fcm_delivery_by_type() {
        // given
        AppNotification chatNotification = AppNotification.create(testUser, chatType, "발신자");
        AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요한사람");

        // then
        assertThat(chatNotification.shouldSendFcm()).isTrue(); // CHAT은 FCM_ONLY
        assertThat(chatNotification.shouldSendSse()).isFalse();
        
        assertThat(likeNotification.shouldSendFcm()).isFalse(); // LIKE는 SSE_ONLY
        assertThat(likeNotification.shouldSendSse()).isTrue();
    }

    @Test
    @DisplayName("여러 인자로 템플릿을 렌더링한다")
    void renders_template_with_multiple_args() {
        // given
        NotificationType commentType = NotificationType.of(
            Type.COMMENT, "%s님이 댓글을 남겼습니다: %s");

        // when
        AppNotification notification = AppNotification.create(
            testUser, commentType, "김철수", "좋은 글이네요!");

        // then
        assertThat(notification.getContent())
            .isEqualTo("김철수님이 댓글을 남겼습니다: 좋은 글이네요!");
    }

    @Test
    @DisplayName("같은 ID를 가진 알림은 동등하다")
    void notifications_with_same_id_are_equal() {
        // given
        AppNotification notification1 = AppNotification.create(testUser, chatType, "발신자1");
        AppNotification notification2 = AppNotification.create(testUser, likeType, "발신자2");

        // ID를 같게 설정 (리플렉션 사용)
        setId(notification1, 100L);
        setId(notification2, 100L);

        // then
        assertThat(notification1).isEqualTo(notification2);
        assertThat(notification1.hashCode()).isEqualTo(notification2.hashCode());
    }

    @Test
    @DisplayName("null 사용자로 알림 생성 시 예외가 발생한다")
    void throws_exception_when_user_is_null() {
        // when & then
        assertThatThrownBy(() -> AppNotification.create(null, chatType, "발신자"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("user cannot be null");
    }

    @Test
    @DisplayName("null 알림타입으로 생성 시 예외가 발생한다")
    void throws_exception_when_notification_type_is_null() {
        // when & then
        assertThatThrownBy(() -> AppNotification.create(testUser, null, "발신자"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("notificationType cannot be null");
    }

    @Test
    @DisplayName("읽음 처리는 멱등하다")
    void marking_as_read_is_idempotent() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "발신자");

        // when
        notification.markAsRead();
        notification.markAsRead(); // 중복 호출

        // then
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("FCM 전송 처리는 멱등하다")
    void marking_fcm_sent_is_idempotent() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "발신자");

        // when
        notification.markFcmSent();
        notification.markFcmSent(); // 중복 호출

        // then
        assertThat(notification.isFcmSent()).isTrue();
    }

    // Helper method
    private void setId(AppNotification notification, Long id) {
        try {
            java.lang.reflect.Field field = AppNotification.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(notification, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}