package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AppNotification 엔티티 테스트")
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
    @DisplayName("UT-NT-001: 알림 생성")
    void utNt001CreatesNotificationWithDefaults() {
        // when
        AppNotification notification = AppNotification.create(testUser, chatType, "테스트");

        // then
        assertThat(notification.getUser()).isEqualTo(testUser);
        assertThat(notification.getNotificationType()).isEqualTo(chatType);
        assertThat(notification.getContent()).isNotBlank();
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.isFcmSent()).isFalse();
        assertThat(notification.isSseSent()).isFalse();
        assertThat(notification.getTargetType()).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("UT-NT-002: 상태 변경")
    void utNt002ChangesNotificationStatus() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "테스트");

        // when & then
        notification.markAsRead();
        assertThat(notification.isRead()).isTrue();
        
        notification.markFcmSent();
        assertThat(notification.isFcmSent()).isTrue();
        
        notification.markSseSent();
        assertThat(notification.isSseSent()).isTrue();
        
        // 멱등성 테스트
        notification.markAsRead();
        notification.markFcmSent();
        notification.markSseSent();
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.isFcmSent()).isTrue();
        assertThat(notification.isSseSent()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-003: 전송 방식별 동작")
    void utNt003DeliveryMethodWorksCorrectly() {
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
    @DisplayName("UT-NT-004: 템플릿 렌더링")
    void utNt004TemplateArgumentsAppliedCorrectly() {
        // given
        NotificationType templateType = NotificationType.of(Type.COMMENT, "댓글 테스트: %s님이 %s에 댓글을 남겼습니다");
        
        // when
        AppNotification notification = AppNotification.create(testUser, templateType, "홍길동", "게시물");
        
        // then
        assertThat(notification.getContent()).contains("홍길동");
        assertThat(notification.getContent()).contains("게시물");
        assertThat(notification.getContent()).contains("댓글을 남겼습니다");
    }

    @Test
    @DisplayName("UT-NT-005: 객체 동등성")
    void utNt005EqualsAndHashCodeWorkCorrectly() {
        // given
        AppNotification notification1 = AppNotification.create(testUser, chatType, "테스트1");
        AppNotification notification2 = AppNotification.create(testUser, chatType, "테스트2");
        
        // Reflection으로 ID 설정 (실제로는 JPA가 설정)
        try {
            Field idField = AppNotification.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(notification1, 1L);
            idField.set(notification2, 1L);
        } catch (Exception e) {
            // 테스트 환경에서 ID 설정 실패 시 무시
        }
        
        // when & then - ID가 같으면 equals true
        assertThat(notification1).isEqualTo(notification2);
        assertThat(notification1.hashCode()).isEqualTo(notification2.hashCode());
        
        // self equality
        assertThat(notification1).isEqualTo(notification1);
        
        // null and different type
        assertThat(notification1).isNotEqualTo(null);
        assertThat(notification1).isNotEqualTo("string");
    }

    @Test
    @DisplayName("UT-NT-006: toString 출력")
    void utNt006ToStringContainsCorrectInformation() {
        // given
        AppNotification notification = AppNotification.create(testUser, chatType, "테스트 내용");
        
        // when
        String toString = notification.toString();
        
        // then - toString이 null이 아니고 기본 정보를 포함하는지만 확인
        assertThat(toString)
                .isNotNull()
                .contains("AppNotification");
    }
}