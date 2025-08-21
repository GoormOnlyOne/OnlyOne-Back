package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("NotificationType 테스트")
class NotificationTypeTest {

    @Test
    @DisplayName("UT-NT-040: 알림 타입별 템플릿이 올바르게 적용되는가?")
    void creates_notification_type_with_auto_delivery_method() {
        // when
        NotificationType chatType = NotificationType.of(Type.CHAT, "테스트 템플릿");
        NotificationType likeType = NotificationType.of(Type.LIKE, "테스트 템플릿");
        NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "테스트 템플릿");

        // then
        assertThat(chatType.getType()).isEqualTo(Type.CHAT);
        assertThat(chatType.getDeliveryMethod()).isEqualTo(DeliveryMethod.FCM_ONLY);
        
        assertThat(likeType.getType()).isEqualTo(Type.LIKE);
        assertThat(likeType.getDeliveryMethod()).isEqualTo(DeliveryMethod.SSE_ONLY);
        
        assertThat(settlementType.getType()).isEqualTo(Type.SETTLEMENT);
        assertThat(settlementType.getDeliveryMethod()).isEqualTo(DeliveryMethod.FCM_ONLY);
    }
}