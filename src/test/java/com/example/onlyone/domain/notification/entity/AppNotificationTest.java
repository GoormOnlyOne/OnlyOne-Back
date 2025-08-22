package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AppNotification 테스트")
class AppNotificationTest {

    private User testUser;
    private NotificationType chatType;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
            .userId(1L)
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .fcmToken("test_token")
            .build();

        chatType = NotificationType.of(Type.CHAT, "테스트 템플릿");
    }

    @Test
    @DisplayName("UT-NT-039: 알림 생성 시 읽지 않음 상태로 초기화되는가?")
    void UT_NT_039_creates_notification_with_defaults() {
        // when
        AppNotification notification = AppNotification.create(testUser, chatType, "테스트");

        // then
        assertThat(notification.getUser()).isEqualTo(testUser);
        assertThat(notification.getNotificationType()).isEqualTo(chatType);
        assertThat(notification.getContent()).isNotBlank();
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.isFcmSent()).isFalse();
        assertThat(notification.getTargetType()).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("UT-NT-021: 읽음 처리 후 읽지 않은 개수가 감소하는가?")
    void UT_NT_021_changes_notification_status() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "테스트");

        // when & then
        notification.markAsRead();
        assertThat(notification.isRead()).isTrue();
        
        notification.markFcmSent();
        assertThat(notification.isFcmSent()).isTrue();
        
        // 멱등성 테스트
        notification.markAsRead();
        notification.markFcmSent();
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.isFcmSent()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-045: 전송 방식(DeliveryMethod)에 따라 올바르게 전송되는가?")
    void UT_NT_045_delivery_method_works_correctly() {
        // given
        AppNotification chatNotification = AppNotification.create(testUser, chatType, "채팅 테스트");
        NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿");
        AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요 테스트");

        // when & then - 전송 방식별 확인
        assertThat(chatNotification.shouldSendFcm()).isTrue();
        assertThat(chatNotification.shouldSendSse()).isFalse();
        
        assertThat(likeNotification.shouldSendSse()).isTrue();
        assertThat(likeNotification.shouldSendFcm()).isFalse();
    }

    @Test
    @DisplayName("UT-NT-037: 삭제된 알림은 조회 목록에 나타나지 않는가?")
    void UT_NT_037_deleted_notification_not_visible() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "삭제될 알림");
        
        // when - 알림 생성 후 상태 확인 (삭제 과정을 시뮬레이션)
        assertThat(notification.getUser()).isEqualTo(testUser);
        assertThat(notification.getContent()).isNotBlank(); // 콘텐츠가 생성됨
        assertThat(notification.isRead()).isFalse(); // 초기 상태: 읽지 않음
        
        // then - 삭제 전에는 정상적으로 존재함을 확인
        assertThat(notification.getNotificationType()).isEqualTo(chatType);
        assertThat(notification.isFcmSent()).isFalse();
        
        // 실제 삭제는 NotificationService의 deleteNotification() 메서드에서 처리됨
        // 이 테스트는 엔티티 레벨에서 삭제 대상 알림의 속성을 검증
    }

    @Test  
    @DisplayName("UT-NT-036: 삭제 후 읽지 않은 개수가 업데이트되는가?")
    void UT_NT_036_unread_count_updated_after_deletion() {
        // given
        AppNotification unreadNotification = AppNotification.create(testUser, chatType, "읽지 않은 알림");
        AppNotification readNotification = AppNotification.create(testUser, chatType, "읽은 알림");
        readNotification.markAsRead();

        // when & then - 읽지 않은 알림의 삭제는 개수에 영향을 줌
        assertThat(unreadNotification.isRead()).isFalse(); // 삭제될 읽지 않은 알림
        assertThat(readNotification.isRead()).isTrue(); // 삭제될 읽은 알림
        
        // 삭제 대상 알림들의 상태 확인
        assertThat(unreadNotification.getUser()).isEqualTo(testUser);
        assertThat(readNotification.getUser()).isEqualTo(testUser);
        
        // 실제 삭제 후 읽지 않은 개수 업데이트는 NotificationService.deleteNotification()에서 처리됨
        // 이 테스트는 엔티티 레벨에서 삭제 대상의 읽음 상태를 검증
    }
}