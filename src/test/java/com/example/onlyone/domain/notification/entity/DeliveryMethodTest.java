package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DeliveryMethod 테스트")
class DeliveryMethodTest {

    @Test
    @DisplayName("UT-NT-045: 전송 방식(DeliveryMethod)에 따라 올바르게 전송되는가?")
    void UT_NT_045_delivery_methods_work_correctly() {
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
    @DisplayName("UT-NT-045: 전송 방식별 SSE/FCM 전송 선택 올바른가?")
    void UT_NT_045_optimal_delivery_method_by_type_is_correct() {
        // 채팅, 전송은 fcm
        assertThat(DeliveryMethod.getOptimalMethod(Type.CHAT)).isEqualTo(DeliveryMethod.FCM_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.SETTLEMENT)).isEqualTo(DeliveryMethod.FCM_ONLY);
        
        // 좋아요, 댓글, 리피드는 SSE
        assertThat(DeliveryMethod.getOptimalMethod(Type.LIKE)).isEqualTo(DeliveryMethod.SSE_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.COMMENT)).isEqualTo(DeliveryMethod.SSE_ONLY);
        assertThat(DeliveryMethod.getOptimalMethod(Type.REFEED)).isEqualTo(DeliveryMethod.SSE_ONLY);
    }
}