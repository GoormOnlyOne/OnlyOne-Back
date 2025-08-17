package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * DeliveryMethod enum 클래식 단위 테스트
 * - 순수 enum 로직 테스트
 * - 비즈니스 규칙 검증
 */
@DisplayName("DeliveryMethod 테스트")
class DeliveryMethodTest {

    @Test
    @DisplayName("FCM_ONLY는 FCM만 전송한다")
    void fcm_only_sends_fcm_only() {
        // given
        DeliveryMethod method = DeliveryMethod.FCM_ONLY;

        // then
        assertThat(method.shouldSendFcm()).isTrue();
        assertThat(method.shouldSendSse()).isFalse();
    }

    @Test
    @DisplayName("SSE_ONLY는 SSE만 전송한다")
    void sse_only_sends_sse_only() {
        // given
        DeliveryMethod method = DeliveryMethod.SSE_ONLY;

        // then
        assertThat(method.shouldSendFcm()).isFalse();
        assertThat(method.shouldSendSse()).isTrue();
    }

    @Test
    @DisplayName("BOTH는 FCM과 SSE 모두 전송한다")
    void both_sends_both_fcm_and_sse() {
        // given
        DeliveryMethod method = DeliveryMethod.BOTH;

        // then
        assertThat(method.shouldSendFcm()).isTrue();
        assertThat(method.shouldSendSse()).isTrue();
    }

    @Test
    @DisplayName("CHAT 타입은 FCM_ONLY가 최적이다")
    void chat_type_optimal_method_is_fcm_only() {
        // when
        DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(Type.CHAT);

        // then
        assertThat(optimal).isEqualTo(DeliveryMethod.FCM_ONLY);
    }

    @Test
    @DisplayName("SETTLEMENT 타입은 FCM_ONLY가 최적이다")
    void settlement_type_optimal_method_is_fcm_only() {
        // when
        DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(Type.SETTLEMENT);

        // then
        assertThat(optimal).isEqualTo(DeliveryMethod.FCM_ONLY);
    }

    @Test
    @DisplayName("LIKE 타입은 SSE_ONLY가 최적이다")
    void like_type_optimal_method_is_sse_only() {
        // when
        DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(Type.LIKE);

        // then
        assertThat(optimal).isEqualTo(DeliveryMethod.SSE_ONLY);
    }

    @Test
    @DisplayName("COMMENT 타입은 SSE_ONLY가 최적이다")
    void comment_type_optimal_method_is_sse_only() {
        // when
        DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(Type.COMMENT);

        // then
        assertThat(optimal).isEqualTo(DeliveryMethod.SSE_ONLY);
    }

    @Test
    @DisplayName("REFEED 타입은 SSE_ONLY가 최적이다")
    void refeed_type_optimal_method_is_sse_only() {
        // when
        DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(Type.REFEED);

        // then
        assertThat(optimal).isEqualTo(DeliveryMethod.SSE_ONLY);
    }

    @Test
    @DisplayName("모든 알림 타입에 대해 최적 전달방식이 정의되어 있다")
    void all_notification_types_have_optimal_delivery_method() {
        // when & then - 모든 Type enum 값에 대해 최적 방식이 정의되어 있는지 확인
        for (Type type : Type.values()) {
            assertThatCode(() -> DeliveryMethod.getOptimalMethod(type))
                .doesNotThrowAnyException();
                
            DeliveryMethod optimal = DeliveryMethod.getOptimalMethod(type);
            assertThat(optimal).isNotNull();
        }
    }

    @Test
    @DisplayName("긴급 알림 타입들은 FCM을 사용한다")
    void urgent_notification_types_use_fcm() {
        // given - 긴급한 알림 타입들
        Type[] urgentTypes = {Type.CHAT, Type.SETTLEMENT};

        // then - 모두 FCM을 사용해야 함
        for (Type type : urgentTypes) {
            DeliveryMethod method = DeliveryMethod.getOptimalMethod(type);
            assertThat(method.shouldSendFcm()).isTrue();
        }
    }

    @Test
    @DisplayName("일반 알림 타입들은 SSE를 사용한다")
    void general_notification_types_use_sse() {
        // given - 일반 알림 타입들
        Type[] generalTypes = {Type.LIKE, Type.COMMENT, Type.REFEED};

        // then - 모두 SSE를 사용해야 함
        for (Type type : generalTypes) {
            DeliveryMethod method = DeliveryMethod.getOptimalMethod(type);
            assertThat(method.shouldSendSse()).isTrue();
            assertThat(method.shouldSendFcm()).isFalse(); // 일반 알림은 FCM 사용하지 않음
        }
    }
}