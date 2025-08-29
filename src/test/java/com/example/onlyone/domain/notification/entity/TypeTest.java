package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Type enum 단위 테스트
 * - 핵심 비즈니스 로직만 검증
 */
@DisplayName("Type enum 테스트")
class TypeTest {

    @Test
    @DisplayName("UT-NT-011: Type TargetType 검증")
    void utNt011TargetTypeMappingIsCorrect() {
        assertThat(Type.CHAT.getTargetType()).isEqualTo("CHAT");
        assertThat(Type.SETTLEMENT.getTargetType()).isEqualTo("SETTLEMENT");
        assertThat(Type.LIKE.getTargetType()).isEqualTo("POST");
        assertThat(Type.COMMENT.getTargetType()).isEqualTo("POST");
        assertThat(Type.REFEED.getTargetType()).isEqualTo("FEED");
    }

}