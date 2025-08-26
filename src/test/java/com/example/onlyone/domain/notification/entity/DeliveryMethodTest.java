package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DeliveryMethod 테스트")
class DeliveryMethodTest {

    @Test
    @DisplayName("UT-NT-009: DeliveryMethod 동작 검증")
    void utNt009DeliveryMethodsWorkCorrectly() {
        // FCM_ONLY
        assertThat(DeliveryMethod.FCM_ONLY.shouldSendFcm()).isTrue();
        assertThat(DeliveryMethod.FCM_ONLY.shouldSendSse()).isFalse();
        
        // SSE_ONLY
        assertThat(DeliveryMethod.SSE_ONLY.shouldSendFcm()).isFalse();
        assertThat(DeliveryMethod.SSE_ONLY.shouldSendSse()).isTrue();
        
        // BOTH
        assertThat(DeliveryMethod.BOTH.shouldSendFcm()).isTrue();
        assertThat(DeliveryMethod.BOTH.shouldSendSse()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-010: 최적 전송 방식 검증")
    void utNt010OptimalDeliveryMethodByTypeIsCorrect() {
        // 채팅, 전송은 fcm
        assertThat(DeliveryMethod.getOptimalMethod(Type.CHAT)).isEqualTo(DeliveryMethod.FCM_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.SETTLEMENT)).isEqualTo(DeliveryMethod.FCM_ONLY);
        
        // 좋아요, 댓글, 리피드는 SSE
        assertThat(DeliveryMethod.getOptimalMethod(Type.LIKE)).isEqualTo(DeliveryMethod.SSE_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.COMMENT)).isEqualTo(DeliveryMethod.SSE_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.REFEED)).isEqualTo(DeliveryMethod.SSE_ONLY);
    }
}