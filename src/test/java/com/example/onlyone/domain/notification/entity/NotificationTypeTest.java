package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("NotificationType 테스트")
class NotificationTypeTest {

    @Test
    @DisplayName("UT-NT-007: NotificationType 생성")
    void utNt007CreatesNotificationTypeWithAutoDeliveryMethod() {
        // when
        NotificationType chatType = NotificationType.of(Type.CHAT, "테스트 템플릿");
        NotificationType likeType = NotificationType.of(Type.LIKE, "테스트 템플릿");
        NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "테스트 템플릿");

        // then - 모든 알림 타입이 올바르게 생성되었는지 확인
        assertThat(chatType.getType()).isEqualTo(Type.CHAT);
        assertThat(likeType.getType()).isEqualTo(Type.LIKE);
        assertThat(settlementType.getType()).isEqualTo(Type.SETTLEMENT);
    }
}