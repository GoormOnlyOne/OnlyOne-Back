package com.example.onlyone.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Type enum 클래식 단위 테스트
 * - 순수 enum 로직 테스트
 * - 타겟 타입 매핑 검증
 */
@DisplayName("Type enum 테스트")
class TypeTest {

    @Test
    @DisplayName("CHAT 타입의 타겟은 CHAT이다")
    void chat_type_target_is_chat() {
        // when
        String targetType = Type.CHAT.getTargetType();

        // then
        assertThat(targetType).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("SETTLEMENT 타입의 타겟은 POST이다")
    void settlement_type_target_is_post() {
        // when
        String targetType = Type.SETTLEMENT.getTargetType();

        // then
        assertThat(targetType).isEqualTo("POST");
    }

    @Test
    @DisplayName("LIKE 타입의 타겟은 POST이다")
    void like_type_target_is_post() {
        // when
        String targetType = Type.LIKE.getTargetType();

        // then
        assertThat(targetType).isEqualTo("POST");
    }

    @Test
    @DisplayName("COMMENT 타입의 타겟은 POST이다")
    void comment_type_target_is_post() {
        // when
        String targetType = Type.COMMENT.getTargetType();

        // then
        assertThat(targetType).isEqualTo("POST");
    }

    @Test
    @DisplayName("REFEED 타입의 타겟은 POST이다")
    void refeed_type_target_is_post() {
        // when
        String targetType = Type.REFEED.getTargetType();

        // then
        assertThat(targetType).isEqualTo("POST");
    }

    @Test
    @DisplayName("모든 알림 타입은 타겟 타입을 가진다")
    void all_notification_types_have_target_type() {
        // when & then
        for (Type type : Type.values()) {
            assertThat(type.getTargetType()).isNotNull();
            assertThat(type.getTargetType()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("채팅 관련 타입은 CHAT 타겟을 가진다")
    void chat_related_types_have_chat_target() {
        // given
        Type[] chatRelatedTypes = {Type.CHAT};

        // then
        for (Type type : chatRelatedTypes) {
            assertThat(type.getTargetType()).isEqualTo("CHAT");
        }
    }

    @Test
    @DisplayName("게시글 관련 타입들은 POST 타겟을 가진다")
    void post_related_types_have_post_target() {
        // given
        Type[] postRelatedTypes = {Type.SETTLEMENT, Type.LIKE, Type.COMMENT, Type.REFEED};

        // then
        for (Type type : postRelatedTypes) {
            assertThat(type.getTargetType()).isEqualTo("POST");
        }
    }

    @Test
    @DisplayName("타겟 타입은 유효한 문자열이다")
    void target_types_are_valid_strings() {
        // when & then
        for (Type type : Type.values()) {
            String targetType = type.getTargetType();
            
            // 타겟 타입은 대문자 영문자로 구성
            assertThat(targetType).matches("^[A-Z]+$");
            
            // 타겟 타입은 예상되는 값 중 하나
            assertThat(targetType).isIn("CHAT", "POST");
        }
    }

    @Test
    @DisplayName("enum 값들이 올바르게 정의되어 있다")
    void enum_values_are_properly_defined() {
        // when
        Type[] types = Type.values();

        // then
        assertThat(types).hasSize(5);
        assertThat(types).containsExactlyInAnyOrder(
            Type.CHAT, Type.SETTLEMENT, Type.LIKE, Type.COMMENT, Type.REFEED
        );
    }

    @Test
    @DisplayName("enum 이름과 문자열 변환이 일치한다")
    void enum_name_and_string_conversion_match() {
        // when & then
        for (Type type : Type.values()) {
            assertThat(Type.valueOf(type.name())).isEqualTo(type);
            assertThat(type.toString()).isEqualTo(type.name());
        }
    }
}