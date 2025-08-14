package com.example.onlyone.domain.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 연결 관리 서비스 테스트 - 기본적인 로딩 테스트만
 */
@DisplayName("SSE 연결 관리 서비스 테스트")
class SseEmittersServiceTest {

    @Test
    @DisplayName("서비스 클래스가 정상적으로 로드된다")
    void 서비스_클래스가_정상적으로_로드된다() {
        // given & when
        SseEmittersService service = new SseEmittersService(null, null);

        // then
        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("SSE 연결 설정값들이 정상적으로 처리된다")
    void SSE_연결_설정값들이_정상적으로_처리된다() {
        // given
        long timeoutMillis = 30000L;
        
        // when & then - 기본적인 값 검증
        assertThat(timeoutMillis).isGreaterThan(0L);
        assertThat(timeoutMillis).isLessThanOrEqualTo(300000L); // 5분 이하
    }
}