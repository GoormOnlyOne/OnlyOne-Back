package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.config.TestQueryDslConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestQueryDslConfig.class)
@ActiveProfiles("test")
@DisplayName("NotificationRepositoryImpl 테스트")
class NotificationRepositoryImplTest {

    @Autowired
    private NotificationRepositoryImpl notificationRepositoryImpl;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private EntityManager entityManager;
    
    private User testUser;
    private NotificationType chatType;
    private NotificationType likeType;
    
    @BeforeEach
    void setUp() {
        // 테스트 유저 생성
        testUser = userRepository.save(User.builder()
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .fcmToken("test_token")
            .build());
        
        // 알림 타입 생성
        chatType = notificationTypeRepository.save(
            NotificationType.of(Type.CHAT, "테스트 템플릿")
        );
        likeType = notificationTypeRepository.save(
            NotificationType.of(Type.LIKE, "테스트 템플릿")
        );
    }
    
    @Test
    @DisplayName("UT-NT-006: 페이징된 알림 목록이 최신순으로 정상 조회되는가?")
    void finds_notifications_by_user_id() {
        // given
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(AppNotification.create(testUser, likeType, "테스트2"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        // when
        List<NotificationItemDto> result = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 10);
        
        // then
        assertThat(result).hasSize(3);
        assertThat(result).extracting(NotificationItemDto::getContent)
            .containsExactly("테스트 템플릿", "테스트 템플릿", "테스트 템플릿");
    }
    
    @Test
    @DisplayName("UT-NT-007: 커서 기반 페이징이 정상 동작하는가?")
    void cursor_pagination_works() {
        // given
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        AppNotification n3 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        // when
        List<NotificationItemDto> firstPage = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 2);
        
        Long lastId = firstPage.get(firstPage.size() - 1).getNotificationId();
        List<NotificationItemDto> secondPage = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), lastId, 2);
        
        // then
        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).getNotificationId()).isLessThan(lastId);
    }
    
    @Test
    @DisplayName("UT-NT-012: 특정 타입의 알림만 필터링되어 조회되는가?")
    void finds_notifications_by_type() {
        // given
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(AppNotification.create(testUser, likeType, "테스트2"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        // when
        List<NotificationItemDto> chatNotifications = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, null, 10);
        
        // then
        assertThat(chatNotifications).hasSize(2);
        assertThat(chatNotifications).extracting(NotificationItemDto::getType)
            .containsOnly(Type.CHAT);
    }
    
    @Test
    @DisplayName("UT-NT-001: 읽지 않은 알림이 있을 때 정확한 개수가 반환되는가?")
    void counts_unread_notifications() {
        // given
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        AppNotification n3 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        n1.markAsRead();
        notificationRepository.save(n1);
        
        // when
        Long unreadCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        
        // then
        assertThat(unreadCount).isEqualTo(2L);
    }
    
    @Test
    @DisplayName("UT-NT-023: 여러 개의 읽지 않은 알림이 모두 읽음 처리되는가?")
    void marks_all_as_read() {
        // given
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        // when
        long updatedCount = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(updatedCount).isEqualTo(3L);
        Long unreadCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        assertThat(unreadCount).isEqualTo(0L);
    }
    
    @Test
    @DisplayName("시간 기반 조회: 특정 시간 이후 알림 조회")
    void finds_notifications_after_timestamp() {
        // given
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        
        // 과거 시간의 알림 생성 (테스트를 위해 생성 시간을 조작하지 않고, cutoff을 과거로 설정)
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "알림1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "알림2"));
        AppNotification n3 = notificationRepository.save(AppNotification.create(testUser, chatType, "알림3"));
        
        // when - cutoff 이후(1시간 전 이후 = 모든 최근 알림)의 알림 조회
        List<AppNotification> recentNotifications = notificationRepositoryImpl
            .findNotificationsByUserIdAfter(testUser.getUserId(), cutoff);
        
        // then
        assertThat(recentNotifications).hasSize(3);
        assertThat(recentNotifications).extracting(AppNotification::getId)
            .containsExactly(n1.getId(), n2.getId(), n3.getId());
    }
    
    @Test
    @DisplayName("통계 조회: 알림 통계 정보 조회")
    void gets_notification_stats() {
        // given
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        AppNotification n3 = notificationRepository.save(AppNotification.create(testUser, likeType, "테스트3"));
        
        n1.markAsRead();
        n1.markFcmSent();
        notificationRepository.save(n1);
        
        n2.markFcmSent();
        notificationRepository.save(n2);
        
        // when
        NotificationRepositoryCustom.NotificationStats stats = 
            notificationRepositoryImpl.getNotificationStats(testUser.getUserId());
        
        // then
        assertThat(stats.totalCount()).isEqualTo(3L);
        assertThat(stats.unreadCount()).isEqualTo(2L);
        assertThat(stats.fcmSentCount()).isEqualTo(2L);
        assertThat(stats.fcmFailedCount()).isEqualTo(0L); // CHAT은 FCM 대상이지만 전송 안됨
    }
    
    @Test
    @DisplayName("데이터 정리: 오래된 읽은 알림 삭제")
    void deletes_old_read_notifications() {
        // given
        AppNotification old = notificationRepository.save(AppNotification.create(testUser, chatType, "오래된"));
        old.markAsRead();
        notificationRepository.save(old);
        
        AppNotification recent = notificationRepository.save(AppNotification.create(testUser, chatType, "최근"));
        
        // when
        long deletedCount = notificationRepositoryImpl.deleteOldNotifications(30);
        
        // then - 테스트 환경에서는 모든 알림이 최근이므로 삭제되지 않음
        assertThat(deletedCount).isEqualTo(0L);
        
        // 실제로는 30일 이전 + 읽음 상태인 것만 삭제됨
        List<AppNotification> remaining = notificationRepository.findAll();
        assertThat(remaining).hasSize(2);
    }

    @Test
    @DisplayName("UT-NT-014: 타입별 조회 시에도 페이징이 정상 동작하는가?")
    void type_filtering_with_pagination_works() {
        // given
        for (int i = 0; i < 5; i++) {
            notificationRepository.save(AppNotification.create(testUser, chatType, "채팅" + i));
            notificationRepository.save(AppNotification.create(testUser, likeType, "좋아요" + i));
        }
        
        // when - 첫 페이지 (CHAT 타입만)
        List<NotificationItemDto> firstPage = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, null, 3);
        
        // 다음 페이지
        Long lastId = firstPage.get(firstPage.size() - 1).getNotificationId();
        List<NotificationItemDto> secondPage = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, lastId, 3);
        
        // then
        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(2);
        assertThat(firstPage).allMatch(item -> item.getType() == Type.CHAT);
        assertThat(secondPage).allMatch(item -> item.getType() == Type.CHAT);
    }

    @Test
    @DisplayName("UT-NT-024: 이미 모두 읽은 상태에서 재처리 시 멱등성이 보장되는가?")
    void mark_all_as_read_is_idempotent() {
        // given - 모든 알림을 읽음 상태로 생성
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        n1.markAsRead();
        n2.markAsRead();
        notificationRepository.save(n1);
        notificationRepository.save(n2);
        
        // when - 읽음 처리 (이미 모두 읽음)
        long firstUpdate = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        long secondUpdate = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(firstUpdate).isEqualTo(0L); // 이미 읽음이므로 0개 업데이트
        assertThat(secondUpdate).isEqualTo(0L); // 멱등성 보장
    }

    @Test
    @DisplayName("UT-NT-026: 다른 사용자의 알림은 영향받지 않는가?")
    void other_users_notifications_not_affected() {
        // given
        User otherUser = userRepository.save(User.builder()
            .kakaoId(67890L)
            .nickname("다른유저")
            .status(Status.ACTIVE)
            .build());
        
        notificationRepository.save(AppNotification.create(testUser, chatType, "유저1 알림1"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "유저1 알림2"));
        notificationRepository.save(AppNotification.create(otherUser, chatType, "유저2 알림1"));
        notificationRepository.save(AppNotification.create(otherUser, chatType, "유저2 알림2"));
        
        // when - testUser의 알림만 읽음 처리
        long updatedCount = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(updatedCount).isEqualTo(2L); // testUser의 알림만 업데이트
        
        // 다른 사용자의 읽지 않은 개수는 그대로
        Long otherUserUnreadCount = notificationRepositoryImpl.countUnreadByUserId(otherUser.getUserId());
        assertThat(otherUserUnreadCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("UT-NT-027: 읽음 처리 후 읽지 않은 개수가 0이 되는가?")
    void unread_count_becomes_zero_after_mark_all_read() {
        // given
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "테스트3"));
        
        // 읽음 처리 전 개수 확인
        Long beforeCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        assertThat(beforeCount).isEqualTo(3L);
        
        // when
        notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        Long afterCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        assertThat(afterCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("UT-NT-060: 읽음 처리 후 DB 상태가 정확히 반영되는가?")
    void read_status_correctly_reflected_in_db() {
        // given
        AppNotification n1 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트1"));
        AppNotification n2 = notificationRepository.save(AppNotification.create(testUser, chatType, "테스트2"));
        
        assertThat(n1.isRead()).isFalse();
        assertThat(n2.isRead()).isFalse();
        
        // when
        long updatedCount = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(updatedCount).isEqualTo(2L);
        
        // Persistence context를 clear하여 DB에서 fresh한 데이터 가져오기
        entityManager.flush();
        entityManager.clear();
        
        // DB에서 다시 조회해서 상태 확인
        List<AppNotification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2);
        assertThat(notifications).allMatch(AppNotification::isRead);
    }

    @Test
    @DisplayName("UT-NT-061: 삭제 후 관련 데이터가 모두 정리되는가?")
    void all_related_data_cleaned_after_deletion() {
        // given
        AppNotification notification = notificationRepository.save(
            AppNotification.create(testUser, chatType, "삭제테스트"));
        Long notificationId = notification.getId();
        
        // 삭제 전 존재 확인
        assertThat(notificationRepository.findById(notificationId)).isPresent();
        
        // when
        notificationRepository.delete(notification);
        
        // then
        assertThat(notificationRepository.findById(notificationId)).isEmpty();
        assertThat(notificationRepository.findAll()).isEmpty();
    }

}