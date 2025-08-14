package com.example.onlyone.domain.notification.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 레포지토리 QueryDSL 구현체 테스트
 * 
 * 실제 데이터베이스 연동 없이 기본 동작만 검증
 */
@DisplayName("알림 레포지토리 구현체 테스트")
class NotificationRepositoryImplTest {

    @Test
    @DisplayName("레포지토리 구현체가 정상적으로 로드된다")
    void 레포지토리_구현체가_정상적으로_로드된다() {
        // given
        NotificationRepositoryImpl repository = new NotificationRepositoryImpl(null);

        // when & then
        assertThat(repository).isNotNull();
        assertThat(repository).isInstanceOf(NotificationRepositoryCustom.class);
    }

    @Test
    @DisplayName("NotificationStats 레코드가 정상적으로 생성된다")
    void NotificationStats_레코드가_정상적으로_생성된다() {
        // given & when
        NotificationRepositoryCustom.NotificationStats stats = 
            new NotificationRepositoryCustom.NotificationStats(100L, 15L, 80L, 5L);

        // then
        assertThat(stats.totalCount()).isEqualTo(100L);
        assertThat(stats.unreadCount()).isEqualTo(15L);
        assertThat(stats.fcmSentCount()).isEqualTo(80L);
        assertThat(stats.fcmFailedCount()).isEqualTo(5L);
    }
}