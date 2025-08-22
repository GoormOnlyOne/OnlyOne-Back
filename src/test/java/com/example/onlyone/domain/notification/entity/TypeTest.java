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
    @DisplayName("UT-NT-040: 알림 타입별 템플릿이 올바르게 적용되는가?")
    void UT_NT_040_target_type_mapping_is_correct() {
        assertThat(Type.CHAT.getTargetType()).isEqualTo("CHAT");
        assertThat(Type.SETTLEMENT.getTargetType()).isEqualTo("SETTLEMENT");
        assertThat(Type.LIKE.getTargetType()).isEqualTo("POST");
        assertThat(Type.COMMENT.getTargetType()).isEqualTo("POST");
        assertThat(Type.REFEED.getTargetType()).isEqualTo("FEED");
    }

    @Test
    @DisplayName("데이터 검증: 모든 타입의 타겟 유효성 검증")
    void UT_NT_062_all_types_have_non_null_target() {
        for (Type type : Type.values()) {
            assertThat(type.getTargetType()).isNotNull().isNotEmpty();
        }
    }
}