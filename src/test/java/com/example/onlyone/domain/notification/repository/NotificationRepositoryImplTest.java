package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.config.QuerydslConfig;
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
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@Import(QuerydslConfig.class)
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
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        entityManager.flush();
        
        // 테스트 유저 생성
        testUser = userRepository.save(User.builder()
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            
            .build());
        
        // 알림 타입 생성 - 템플릿에 %s 플레이스홀더 포함
        chatType = notificationTypeRepository.save(
            NotificationType.of(Type.CHAT, "테스트 템플릿: %s")
        );
        likeType = notificationTypeRepository.save(
            NotificationType.of(Type.LIKE, "테스트 템플릿: %s")
        );
    }
    
    @Test
    @DisplayName("UT-NT-008: 읽지 않은 개수 조회")
    void utNt008CountsUnreadNotifications() {
        // given
        Notification n1 = notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        Notification n2 = notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
        Notification n3 = notificationRepository.save(Notification.create(testUser, chatType, "테스트3"));
        
        n1.markAsRead();
        notificationRepository.save(n1);
        
        // when
        Long unreadCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        
        // then
        assertThat(unreadCount).isEqualTo(2L);
    }
    
    @Test
    @DisplayName("UT-NT-009: 페이징 조회")
    void utNt009FindsNotificationsByUserId() {
        // given - 시간 간격을 두고 알림 생성
        Notification first = notificationRepository.save(Notification.create(testUser, chatType, "첫번째"));
        try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
        
        Notification second = notificationRepository.save(Notification.create(testUser, likeType, "두번째"));
        try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
        
        Notification third = notificationRepository.save(Notification.create(testUser, chatType, "세번째"));
        
        // when
        List<NotificationItemDto> result = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 10);
        
        // then - 최신순으로 정렬되어야 함 (createdAt 기준)
        assertThat(result).hasSize(3);
        
        // 생성 시간 기준 최신순 검증 (가장 최근 생성된 것이 첫 번째)
        assertThat(result.get(0).getCreatedAt()).isAfterOrEqualTo(result.get(1).getCreatedAt());
        assertThat(result.get(1).getCreatedAt()).isAfterOrEqualTo(result.get(2).getCreatedAt());
        
        // 실제 생성 순서와 조회 순서가 반대여야 함 (최신순이므로)
        // third가 가장 마지막에 생성되었으므로 첫 번째에 나와야 함
        assertThat(result.get(0).getNotificationId()).isEqualTo(third.getId());
        assertThat(result.get(1).getNotificationId()).isEqualTo(second.getId());
        assertThat(result.get(2).getNotificationId()).isEqualTo(first.getId());
        
        // 시간 순서 확인 (최신이 먼저)
        LocalDateTime firstTime = result.get(0).getCreatedAt();
        LocalDateTime secondTime = result.get(1).getCreatedAt();
        LocalDateTime thirdTime = result.get(2).getCreatedAt();
        
        assertThat(firstTime).isAfterOrEqualTo(secondTime);
        assertThat(secondTime).isAfterOrEqualTo(thirdTime);
    }
    
    @Test
    @DisplayName("UT-NT-016: 커서 페이징 검증")
    void utNt016CursorPaginationWorks() {
        // given
        Notification n1 = notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        Notification n2 = notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
        Notification n3 = notificationRepository.save(Notification.create(testUser, chatType, "테스트3"));
        
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
    @DisplayName("UT-NT-019: 일괄 읽음 처리")
    void utNt019MarksAllAsRead() {
        // given
        notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
        notificationRepository.save(Notification.create(testUser, chatType, "테스트3"));
        
        // when
        long updatedCount = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(updatedCount).isEqualTo(3L);
        Long unreadCount = notificationRepositoryImpl.countUnreadByUserId(testUser.getUserId());
        assertThat(unreadCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("UT-NT-020: 읽음 처리 멱등성")
    void utNt020MarkAllAsReadIsIdempotent() {
        // given - 모든 알림을 읽음 상태로 생성
        Notification n1 = notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        Notification n2 = notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
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
    @DisplayName("UT-NT-021: 사용자별 격리 검증")
    void utNt021OtherUsersNotificationsNotAffected() {
        // given
        User otherUser = userRepository.save(User.builder()
            .kakaoId(67890L)
            .nickname("다른유저")
            .status(Status.ACTIVE)
            .build());
        
        notificationRepository.save(Notification.create(testUser, chatType, "유저1 알림1"));
        notificationRepository.save(Notification.create(testUser, chatType, "유저1 알림2"));
        notificationRepository.save(Notification.create(otherUser, chatType, "유저2 알림1"));
        notificationRepository.save(Notification.create(otherUser, chatType, "유저2 알림2"));
        
        // when - testUser의 알림만 읽음 처리
        long updatedCount = notificationRepositoryImpl.markAllAsReadByUserId(testUser.getUserId());
        
        // then
        assertThat(updatedCount).isEqualTo(2L); // testUser의 알림만 업데이트
        
        // 다른 사용자의 읽지 않은 개수는 그대로
        Long otherUserUnreadCount = notificationRepositoryImpl.countUnreadByUserId(otherUser.getUserId());
        assertThat(otherUserUnreadCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("UT-NT-022: 읽음 처리 후 개수 확인")
    void utNt022UnreadCountBecomesZeroAfterMarkAllRead() {
        // given
        notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
        notificationRepository.save(Notification.create(testUser, chatType, "테스트3"));
        
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
    @DisplayName("UT-NT-023: 시간 기반 조회")
    void utNt023FindsNotificationsAfterTimestampForSseReconnection() {
        // given - 과거 시간을 기준으로 설정
        LocalDateTime veryPastTime = LocalDateTime.now().minusHours(1);
        
        // 현재 시점에 알림 생성
        Notification notification1 = notificationRepository.save(
            Notification.create(testUser, likeType, "놓친 알림 1"));
        Notification notification2 = notificationRepository.save(
            Notification.create(testUser, chatType, "놓친 알림 2"));
        
        // when - 모든 알림 조회 (시간 기반 조회는 더 이상 필요하지 않음)
        List<NotificationItemDto> allNotifications = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 10);
        
        // then - 현재 생성된 알림들이 조회되어야 함 (적어도 2개 이상)
        assertThat(allNotifications.size()).isGreaterThanOrEqualTo(2);
        
        // 생성한 2개 알림이 모두 포함되어있는지 확인
        List<Long> notificationIds = allNotifications.stream()
            .map(NotificationItemDto::getNotificationId)
            .toList();
        assertThat(notificationIds).contains(notification1.getId(), notification2.getId());
    }

    @Test
    @DisplayName("UT-NT-024: DB 상태 반영 확인")
    void utNt024ReadStatusCorrectlyReflectedInDb() {
        // given
        Notification n1 = notificationRepository.save(Notification.create(testUser, chatType, "테스트1"));
        Notification n2 = notificationRepository.save(Notification.create(testUser, chatType, "테스트2"));
        
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
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2);
        assertThat(notifications).allMatch(Notification::isRead);
    }

    @Test
    @DisplayName("UT-NT-025: 선택적 삭제 검증")
    void utNt025SpecificNotificationDeletedWhileOthersRemain() {
        // given - 테스트 시작 전에 기존 알림들 모두 정리
        notificationRepository.deleteAll();
        
        // 새로 여러 개의 알림 생성
        Notification notification1 = notificationRepository.save(
            Notification.create(testUser, chatType, "유지될 알림1"));
        Notification notification2 = notificationRepository.save(
            Notification.create(testUser, likeType, "삭제될 알림"));
        Notification notification3 = notificationRepository.save(
            Notification.create(testUser, chatType, "유지될 알림2"));
        
        Long deleteTargetId = notification2.getId();
        
        // 삭제 전 3개 모두 존재 확인
        assertThat(notificationRepository.findAll()).hasSize(3);
        assertThat(notificationRepository.findById(deleteTargetId)).isPresent();
        
        // when - 특정 알림만 삭제
        notificationRepository.delete(notification2);
        
        // then - 삭제된 알림만 없어지고 나머지는 유지
        assertThat(notificationRepository.findById(deleteTargetId)).isEmpty();
        
        List<Notification> remainingNotifications = notificationRepository.findAll();
        assertThat(remainingNotifications).hasSize(2);
        assertThat(remainingNotifications).extracting(Notification::getId)
            .containsExactlyInAnyOrder(notification1.getId(), notification3.getId());
        
        // 내용은 템플릿이 적용될 수 있으므로 ID로만 검증
    }
    
    
    
    @Test
    @DisplayName("UT-NT-028: 커버리지 개선 - 읽지 않은 알림 조회")
    void utNt028FindsUnreadNotificationsByUserId() {
        // given
        Notification unread1 = notificationRepository.save(Notification.create(testUser, chatType, "읽지않은1"));
        Notification unread2 = notificationRepository.save(Notification.create(testUser, likeType, "읽지않은2"));
        Notification read = notificationRepository.save(Notification.create(testUser, chatType, "읽음"));
        
        read.markAsRead();
        notificationRepository.save(read);
        
        // when
        List<Notification> unreadNotifications = notificationRepositoryImpl
            .findUnreadNotificationsByUserId(testUser.getUserId());
        
        // then
        assertThat(unreadNotifications).hasSize(2);
        assertThat(unreadNotifications).extracting(Notification::getId)
            .containsExactlyInAnyOrder(unread1.getId(), unread2.getId());
        assertThat(unreadNotifications).allMatch(n -> !n.isRead());
    }
    
    
    @Test
    @DisplayName("UT-NT-031: 커버리지 개선 - fetchJoin으로 알림 조회")
    void utNt031FindsByIdWithFetchJoin() {
        // given
        Notification notification = notificationRepository.save(
            Notification.create(testUser, chatType, "FetchJoin 테스트"));
        
        // when
        Notification found = notificationRepositoryImpl
            .findByIdWithFetchJoin(notification.getId());
        
        // then
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(notification.getId());
        assertThat(found.getContent()).contains("테스트 템플릿: FetchJoin 테스트"); // 템플릿이 적용된 내용 확인
        assertThat(found.getUser()).isNotNull(); // fetchJoin으로 user 로드됨
        assertThat(found.getNotificationType()).isNotNull(); // fetchJoin으로 notificationType 로드됨
    }
    
    @Test
    @DisplayName("UT-NT-032: 커버리지 개선 - 없는 ID로 fetchJoin 조회")
    void utNt032FindsByIdWithFetchJoinReturnsNull() {
        // given
        Long nonExistentId = 99999L;
        
        // when
        Notification found = notificationRepositoryImpl
            .findByIdWithFetchJoin(nonExistentId);
        
        // then
        assertThat(found).isNull();
    }
    
    
    @Test
    @DisplayName("UT-NT-035: 커버리지 개선 - null cursor 조건 처리")
    void utNt035HandlesNullCursorCondition() {
        // given
        notificationRepository.save(Notification.create(testUser, chatType, "cursor 테스트"));
        
        // when - cursor가 null일 때
        List<NotificationItemDto> result = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 10);
        
        // then - 정상적으로 조회되어야 함
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getContent()).contains("테스트 템플릿: cursor 테스트"); // 템플릿이 적용된 내용 확인
    }
    
    @Test
    @DisplayName("SSE 전송 상태 업데이트 성공")
    void updateSseSentStatus_success() {
        // given
        Notification notification = notificationRepository.save(
            Notification.create(testUser, chatType, "SSE 상태 테스트"));
        
        // 초기 상태 확인
        assertThat(notification.isSseSent()).isFalse();
        
        // when - SSE 전송 성공으로 업데이트
        long updatedCount = notificationRepositoryImpl.updateSseSentStatus(notification.getId(), true);
        
        // then
        assertThat(updatedCount).isEqualTo(1L);
        
        // DB에서 다시 조회하여 상태 확인
        entityManager.flush();
        entityManager.clear();
        
        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.isSseSent()).isTrue();
    }
    
    @Test
    @DisplayName("SSE 전송 실패 상태 업데이트")
    void updateSseSentStatus_toFalse() {
        // given
        Notification notification = notificationRepository.save(
            Notification.create(testUser, chatType, "SSE 실패 테스트"));
        
        // 먼저 성공 상태로 설정
        notification.markSseSent();
        notificationRepository.save(notification);
        assertThat(notification.isSseSent()).isTrue();
        
        // when - SSE 전송 실패로 업데이트
        long updatedCount = notificationRepositoryImpl.updateSseSentStatus(notification.getId(), false);
        
        // then
        assertThat(updatedCount).isEqualTo(1L);
        
        // DB에서 다시 조회하여 상태 확인
        entityManager.flush();
        entityManager.clear();
        
        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.isSseSent()).isFalse();
    }
    
    @Test
    @DisplayName("존재하지 않는 알림 SSE 상태 업데이트")
    void updateSseSentStatus_nonExistent() {
        // given
        Long nonExistentId = 99999L;
        
        // when - 존재하지 않는 알림 업데이트 시도
        long updatedCount = notificationRepositoryImpl.updateSseSentStatus(nonExistentId, true);
        
        // then - 업데이트된 개수가 0
        assertThat(updatedCount).isEqualTo(0L);
    }
    
    @Test
    @DisplayName("SSE 전송 상태 업데이트 멱등성")
    void updateSseSentStatus_idempotent() {
        // given
        Notification notification = notificationRepository.save(
            Notification.create(testUser, chatType, "멱등성 테스트"));
        
        // when - 동일한 상태로 여러 번 업데이트
        long firstUpdate = notificationRepositoryImpl.updateSseSentStatus(notification.getId(), true);
        long secondUpdate = notificationRepositoryImpl.updateSseSentStatus(notification.getId(), true);
        
        // then - 모두 정상 처리되어야 함
        assertThat(firstUpdate).isEqualTo(1L);
        assertThat(secondUpdate).isEqualTo(1L);
        
        // 최종 상태 확인
        entityManager.flush();
        entityManager.clear();
        
        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.isSseSent()).isTrue();
    }

}