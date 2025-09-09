package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("알림 중복 제거 서비스 테스트")
class NotificationDeduplicationServiceTest {
    
    private NotificationDeduplicationService deduplicationService;
    
    @BeforeEach
    void setUp() {
        deduplicationService = new NotificationDeduplicationService();
        // 테스트용 설정값 주입
        ReflectionTestUtils.setField(deduplicationService, "deduplicationWindowSeconds", 5);
        ReflectionTestUtils.setField(deduplicationService, "maxEventAgeMinutes", 10);
    }
    
    @Test
    @DisplayName("새로운 알림은 전송이 허용되어야 한다")
    void shouldAllowNewNotification() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        
        // when
        boolean result = deduplicationService.shouldSendNotification(userId, type, entityId, now);
        
        // then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("중복 제거 윈도우 내의 동일한 알림은 차단되어야 한다")
    void shouldBlockDuplicateNotificationWithinWindow() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        
        // when
        boolean firstResult = deduplicationService.shouldSendNotification(userId, type, entityId, now);
        boolean secondResult = deduplicationService.shouldSendNotification(userId, type, entityId, now.plusSeconds(3));
        
        // then
        assertThat(firstResult).isTrue();
        assertThat(secondResult).isFalse();
    }
    
    @Test
    @DisplayName("중복 제거 윈도우를 벗어난 동일한 알림은 허용되어야 한다")
    void shouldAllowNotificationAfterDeduplicationWindow() throws InterruptedException {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        
        // when
        boolean firstResult = deduplicationService.shouldSendNotification(userId, type, entityId, now);
        
        // 6초 후 (윈도우 크기 5초를 초과)
        boolean secondResult = deduplicationService.shouldSendNotification(userId, type, entityId, now.plusSeconds(6));
        
        // then
        assertThat(firstResult).isTrue();
        assertThat(secondResult).isTrue();
    }
    
    @Test
    @DisplayName("다른 사용자의 동일한 알림은 허용되어야 한다")
    void shouldAllowSameNotificationForDifferentUsers() {
        // given
        Long userId1 = 1L;
        Long userId2 = 2L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        
        // when
        boolean user1Result = deduplicationService.shouldSendNotification(userId1, type, entityId, now);
        boolean user2Result = deduplicationService.shouldSendNotification(userId2, type, entityId, now);
        
        // then
        assertThat(user1Result).isTrue();
        assertThat(user2Result).isTrue();
    }
    
    @Test
    @DisplayName("다른 타입의 알림은 허용되어야 한다")
    void shouldAllowDifferentNotificationTypes() {
        // given
        Long userId = 1L;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        
        // when
        boolean commentResult = deduplicationService.shouldSendNotification(userId, Type.COMMENT, entityId, now);
        boolean likeResult = deduplicationService.shouldSendNotification(userId, Type.LIKE, entityId, now);
        
        // then
        assertThat(commentResult).isTrue();
        assertThat(likeResult).isTrue();
    }
    
    @Test
    @DisplayName("오래된 이벤트는 차단되어야 한다")
    void shouldBlockOldEvents() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime oldTime = LocalDateTime.now().minusMinutes(15); // 10분 초과
        
        // when
        boolean result = deduplicationService.shouldSendNotification(userId, type, entityId, oldTime);
        
        // then
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("중복된 알림의 통합 메시지를 생성해야 한다")
    void shouldCreateMergedNotificationMessage() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        String entityId = "post-123";
        LocalDateTime now = LocalDateTime.now();
        String originalMessage = "새 댓글이 있습니다";
        
        // 첫 번째 알림 등록
        deduplicationService.shouldSendNotification(userId, type, entityId, now);
        
        // 중복 알림들 등록 (차단되지만 카운트는 증가)
        deduplicationService.shouldSendNotification(userId, type, entityId, now.plusSeconds(1));
        deduplicationService.shouldSendNotification(userId, type, entityId, now.plusSeconds(2));
        
        // when
        String mergedMessage = deduplicationService.createMergedNotificationMessage(userId, type, entityId, originalMessage);
        
        // then
        assertThat(mergedMessage).contains("외 2건");
    }
    
    @Test
    @DisplayName("중복 제거 엔트리 수를 조회할 수 있어야 한다")
    void shouldReturnActiveDeduplicationEntries() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        LocalDateTime now = LocalDateTime.now();
        
        // when
        deduplicationService.shouldSendNotification(userId, type, "entity-1", now);
        deduplicationService.shouldSendNotification(userId, type, "entity-2", now);
        deduplicationService.shouldSendNotification(userId, type, "entity-3", now);
        
        int activeEntries = deduplicationService.getActiveDeduplicationEntries();
        
        // then
        assertThat(activeEntries).isEqualTo(3);
    }
    
    @Test
    @DisplayName("정리 작업이 만료된 이벤트를 제거해야 한다")
    void shouldCleanupExpiredEvents() {
        // given
        Long userId = 1L;
        Type type = Type.COMMENT;
        LocalDateTime now = LocalDateTime.now();
        
        deduplicationService.shouldSendNotification(userId, type, "entity-1", now);
        
        // 초기 엔트리 수 확인
        int initialEntries = deduplicationService.getActiveDeduplicationEntries();
        assertThat(initialEntries).isEqualTo(1);
        
        // when - 정리 작업 실행 (만료된 항목 제거)
        deduplicationService.cleanupExpiredEvents();
        
        // then - 아직 만료되지 않았으므로 유지
        int afterCleanup = deduplicationService.getActiveDeduplicationEntries();
        assertThat(afterCleanup).isEqualTo(1);
    }
}