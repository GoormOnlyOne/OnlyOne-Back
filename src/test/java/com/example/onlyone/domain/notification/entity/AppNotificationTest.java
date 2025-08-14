package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 엔티티 테스트
 */
@DisplayName("알림 엔티티 테스트")
class AppNotificationTest {

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // given - 테스트 데이터 설정
        testUser = createTestUser(1L, "테스트유저");
        testNotificationType = NotificationType.of(Type.CHAT, "%s님이 메시지를 보냈습니다.");
    }

    @Nested
    @DisplayName("알림 생성")
    class CreateNotificationTest {

        @Test
        @DisplayName("정상적인 매개변수로 알림을 성공적으로 생성한다")
        void 정상적인_매개변수로_알림을_성공적으로_생성한다() {
            // given
            String[] args = {"홍길동"};

            // when
            AppNotification notification = AppNotification.create(testUser, testNotificationType, args);

            // then
            assertThat(notification.getUser()).isEqualTo(testUser);
            assertThat(notification.getNotificationType()).isEqualTo(testNotificationType);
            assertThat(notification.getContent()).contains("홍길동");
            assertThat(notification.isRead()).isFalse();
            assertThat(notification.isFcmSent()).isFalse();
        }

        @Test
        @DisplayName("null 사용자로 알림 생성 시 예외가 발생한다")
        void null_사용자로_알림_생성_시_예외가_발생한다() {
            // given
            User nullUser = null;
            String[] args = {"테스트"};

            // when & then
            assertThatThrownBy(() -> AppNotification.create(nullUser, testNotificationType, args))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null 알림 타입으로 알림 생성 시 예외가 발생한다")
        void null_알림_타입으로_알림_생성_시_예외가_발생한다() {
            // given
            NotificationType nullType = null;
            String[] args = {"테스트"};

            // when & then
            assertThatThrownBy(() -> AppNotification.create(testUser, nullType, args))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("알림 상태 변경")
    class ChangeNotificationStateTest {

        @Test
        @DisplayName("알림을 읽음 상태로 변경한다")
        void 알림을_읽음_상태로_변경한다() {
            // given
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트");
            assertThat(notification.isRead()).isFalse();

            // when
            notification.markAsRead();

            // then
            assertThat(notification.isRead()).isTrue();
        }

        @Test
        @DisplayName("FCM 전송 상태를 성공으로 변경한다")
        void FCM_전송_상태를_성공으로_변경한다() {
            // given
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트");
            assertThat(notification.isFcmSent()).isFalse();

            // when
            notification.markFcmSent();

            // then
            assertThat(notification.isFcmSent()).isTrue();
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}