package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.domain.interest.exception.InterestErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Category 단위 테스트")
class CategoryTest {

    @Test
    @DisplayName("성공: 대문자 CULTURE → CULTURE")
    void fromUpperCase() {
        assertThat(Category.from("CULTURE")).isEqualTo(Category.CULTURE);
    }

    @Test
    @DisplayName("성공: 소문자 exercise → EXERCISE (대소문자 무시)")
    void fromLowerCase() {
        assertThat(Category.from("exercise")).isEqualTo(Category.EXERCISE);
    }

    @Test
    @DisplayName("성공: 모든 8개 카테고리 변환")
    void allCategoriesResolvable() {
        assertThat(Category.from("CULTURE")).isEqualTo(Category.CULTURE);
        assertThat(Category.from("EXERCISE")).isEqualTo(Category.EXERCISE);
        assertThat(Category.from("TRAVEL")).isEqualTo(Category.TRAVEL);
        assertThat(Category.from("MUSIC")).isEqualTo(Category.MUSIC);
        assertThat(Category.from("CRAFT")).isEqualTo(Category.CRAFT);
        assertThat(Category.from("SOCIAL")).isEqualTo(Category.SOCIAL);
        assertThat(Category.from("LANGUAGE")).isEqualTo(Category.LANGUAGE);
        assertThat(Category.from("FINANCE")).isEqualTo(Category.FINANCE);
    }

    @Test
    @DisplayName("실패: 존재하지 않는 값 → INVALID_CATEGORY")
    void failUnknownCategory() {
        assertThatThrownBy(() -> Category.from("UNKNOWN"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(InterestErrorCode.INVALID_CATEGORY);
    }
}
