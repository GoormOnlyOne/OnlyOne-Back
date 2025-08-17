package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * NotificationType 엔티티 클래식 단위 테스트
 * - Mock 사용하지 않음
 * - 순수 도메인 로직만 테스트
 */
@DisplayName("NotificationType 클래식 테스트")
class NotificationTypeClassicalTest {

    @Test
    @DisplayName("기본 팩토리 메서드로 알림타입을 생성한다")
    void creates_notification_type_with_default_factory() {
        // when
        NotificationType chatType = NotificationType.of(Type.CHAT, "%s님이 메시지를 보냈습니다.");

        // then
        assertThat(chatType.getType()).isEqualTo(Type.CHAT);
        assertThat(chatType.getTemplate()).isEqualTo("%s님이 메시지를 보냈습니다.");
        assertThat(chatType.getDeliveryMethod()).isEqualTo(DeliveryMethod.FCM_ONLY); // CHAT의 기본값
    }

    @Test
    @DisplayName("전달방식을 지정하여 알림타입을 생성한다")
    void creates_notification_type_with_specific_delivery_method() {
        // when
        NotificationType customType = NotificationType.of(
            Type.LIKE, 
            "%s님이 좋아합니다.", 
            DeliveryMethod.BOTH
        );

        // then
        assertThat(customType.getType()).isEqualTo(Type.LIKE);
        assertThat(customType.getTemplate()).isEqualTo("%s님이 좋아합니다.");
        assertThat(customType.getDeliveryMethod()).isEqualTo(DeliveryMethod.BOTH);
    }

    @Test
    @DisplayName("템플릿을 인자로 렌더링한다")
    void renders_template_with_args() {
        // given
        NotificationType chatType = NotificationType.of(Type.CHAT, "%s님이 메시지를 보냈습니다.");

        // when
        String rendered = chatType.render("홍길동");

        // then
        assertThat(rendered).isEqualTo("홍길동님이 메시지를 보냈습니다.");
    }

    @Test
    @DisplayName("여러 인자로 템플릿을 렌더링한다")
    void renders_template_with_multiple_args() {
        // given
        NotificationType commentType = NotificationType.of(
            Type.COMMENT, "%s님이 %s에 댓글을 남겼습니다: %s");

        // when
        String rendered = commentType.render("김철수", "게시글", "좋은 글이네요!");

        // then
        assertThat(rendered).isEqualTo("김철수님이 게시글에 댓글을 남겼습니다: 좋은 글이네요!");
    }

    @Test
    @DisplayName("인자가 없으면 원본 템플릿을 반환한다")
    void returns_original_template_when_no_args() {
        // given
        NotificationType type = NotificationType.of(Type.LIKE, "고정 메시지입니다.");

        // when
        String rendered = type.render();

        // then
        assertThat(rendered).isEqualTo("고정 메시지입니다.");
    }

    @Test
    @DisplayName("null 인자 배열도 안전하게 처리한다")
    void handles_null_args_safely() {
        // given
        NotificationType type = NotificationType.of(Type.LIKE, "기본 메시지");

        // when
        String rendered = type.render((String[]) null);

        // then
        assertThat(rendered).isEqualTo("기본 메시지");
    }

    @Test
    @DisplayName("인자가 부족하면 포맷팅 예외가 발생한다")
    void throws_exception_when_insufficient_args() {
        // given
        NotificationType type = NotificationType.of(Type.COMMENT, "%s님이 %s에 댓글을 남겼습니다.");

        // when & then - 인자 부족 시 포맷팅 예외 발생
        assertThatThrownBy(() -> type.render("홍길동")) // 인자 1개만 제공
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("같은 ID를 가진 알림타입은 동등하다")
    void notification_types_with_same_id_are_equal() {
        // given
        NotificationType type1 = NotificationType.of(Type.CHAT, "템플릿1");
        NotificationType type2 = NotificationType.of(Type.LIKE, "템플릿2");

        // ID를 같게 설정
        setId(type1, 100L);
        setId(type2, 100L);

        // then
        assertThat(type1).isEqualTo(type2);
        assertThat(type1.hashCode()).isEqualTo(type2.hashCode());
    }

    @Test
    @DisplayName("타입별 최적 전달방식이 올바르게 설정된다")
    void sets_optimal_delivery_method_by_type() {
        // when
        NotificationType chatType = NotificationType.of(Type.CHAT, "템플릿");
        NotificationType likeType = NotificationType.of(Type.LIKE, "템플릿");
        NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "템플릿");

        // then
        assertThat(chatType.getDeliveryMethod()).isEqualTo(DeliveryMethod.FCM_ONLY);
        assertThat(likeType.getDeliveryMethod()).isEqualTo(DeliveryMethod.SSE_ONLY);
        assertThat(settlementType.getDeliveryMethod()).isEqualTo(DeliveryMethod.FCM_ONLY);
    }

    @Test
    @DisplayName("null 타입으로 생성 시 예외가 발생한다")
    void throws_exception_when_type_is_null() {
        // when & then
        assertThatThrownBy(() -> NotificationType.of(null, "템플릿"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("type cannot be null");
    }

    @Test
    @DisplayName("null 템플릿으로 생성 시 예외가 발생한다")
    void throws_exception_when_template_is_null() {
        // when & then
        assertThatThrownBy(() -> NotificationType.of(Type.CHAT, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("template cannot be null");
    }

    @Test
    @DisplayName("null 전달방식으로 생성 시 예외가 발생한다")
    void throws_exception_when_delivery_method_is_null() {
        // when & then
        assertThatThrownBy(() -> NotificationType.of(Type.CHAT, "템플릿", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("deliveryMethod cannot be null");
    }

    // Helper method
    private void setId(NotificationType notificationType, Long id) {
        try {
            java.lang.reflect.Field field = NotificationType.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(notificationType, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}