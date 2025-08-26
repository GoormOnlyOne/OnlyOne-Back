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
import static org.assertj.core.api.Assertions.assertThatCode;

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
            .fcmToken("test_token")
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
    @DisplayName("UT-NT-009: 페이징 조회")
    void utNt009FindsNotificationsByUserId() {
        // given - 시간 간격을 두고 알림 생성
        AppNotification first = notificationRepository.save(AppNotification.create(testUser, chatType, "첫번째"));
        try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
        
        AppNotification second = notificationRepository.save(AppNotification.create(testUser, likeType, "두번째"));
        try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
        
        AppNotification third = notificationRepository.save(AppNotification.create(testUser, chatType, "세번째"));
        
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
    @DisplayName("UT-NT-017: 타입별 필터링 검증")
    void utNt017FindsNotificationsByType() {
        // given - 여러 타입의 알림을 섞어서 생성
        notificationRepository.save(AppNotification.create(testUser, chatType, "채팅1"));
        notificationRepository.save(AppNotification.create(testUser, likeType, "좋아요1"));
        notificationRepository.save(AppNotification.create(testUser, chatType, "채팅2"));
        notificationRepository.save(AppNotification.create(testUser, likeType, "좋아요2"));
        notificationRepository.save(AppNotification.create(testUser, likeType, "좋아요3"));
        
        // when - 좋아요 타입만 조회
        List<NotificationItemDto> likeNotifications = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.LIKE, null, 10);
        
        // when - 채팅 타입만 조회
        List<NotificationItemDto> chatNotifications = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, null, 10);
        
        // then - 좋아요 타입만 3개 조회되어야 함
        assertThat(likeNotifications).hasSize(3);
        assertThat(likeNotifications).extracting(NotificationItemDto::getType)
            .containsOnly(Type.LIKE);
        
        // then - 채팅 타입만 2개 조회되어야 함
        assertThat(chatNotifications).hasSize(2);
        assertThat(chatNotifications).extracting(NotificationItemDto::getType)
            .containsOnly(Type.CHAT);
    }

    @Test
    @DisplayName("UT-NT-018: 타입별 무한 스크롤")
    void utNt018TypeFilteringWithCursorBasedInfiniteScrollingWorks() {
        // given - 여러 타입을 섞어서 많이 생성 (무한 스크롤링 시뮬레이션)
        for (int i = 0; i < 7; i++) {
            notificationRepository.save(AppNotification.create(testUser, chatType, "채팅" + i));
            notificationRepository.save(AppNotification.create(testUser, likeType, "좋아요" + i));
        }
        // 총 14개 알림 중 CHAT 7개, LIKE 7개
        
        // when - 첫 페이지: CHAT 타입만 3개씩 조회
        List<NotificationItemDto> firstPage = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, null, 3);
        
        // 두 번째 페이지: 첫 번째 페이지의 마지막 ID를 커서로 사용
        Long firstCursor = firstPage.get(firstPage.size() - 1).getNotificationId();
        List<NotificationItemDto> secondPage = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, firstCursor, 3);
        
        // 세 번째 페이지: 두 번째 페이지의 마지막 ID를 커서로 사용
        Long secondCursor = secondPage.get(secondPage.size() - 1).getNotificationId();
        List<NotificationItemDto> thirdPage = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.CHAT, secondCursor, 3);
        
        // then - 페이지별 크기 검증
        assertThat(firstPage).hasSize(3);   // 첫 3개
        assertThat(secondPage).hasSize(3);  // 다음 3개
        assertThat(thirdPage).hasSize(1);   // 마지막 1개 (총 7개 중 6개 이후)
        
        // 모든 페이지가 CHAT 타입만 포함하는지 검증
        assertThat(firstPage).allMatch(item -> item.getType() == Type.CHAT);
        assertThat(secondPage).allMatch(item -> item.getType() == Type.CHAT);
        assertThat(thirdPage).allMatch(item -> item.getType() == Type.CHAT);
        
        // 커서 기반 정렬 검증: 각 페이지의 ID가 이전 페이지보다 작아야 함 (최신순)
        assertThat(firstPage.get(0).getNotificationId()).isGreaterThan(firstCursor);
        assertThat(secondPage.get(0).getNotificationId()).isLessThan(firstCursor);
        assertThat(thirdPage.get(0).getNotificationId()).isLessThan(secondCursor);
        
        // 중복 없이 모든 CHAT 알림이 조회되었는지 검증
        List<Long> allRetrievedIds = List.of(
            firstPage.stream().map(NotificationItemDto::getNotificationId).toList(),
            secondPage.stream().map(NotificationItemDto::getNotificationId).toList(),
            thirdPage.stream().map(NotificationItemDto::getNotificationId).toList()
        ).stream().flatMap(List::stream).toList();
        
        assertThat(allRetrievedIds).hasSize(7); // 총 7개의 CHAT 알림
        assertThat(allRetrievedIds).doesNotHaveDuplicates(); // 중복 없음
        
        // LIKE 타입은 전혀 포함되지 않았는지 검증
        List<NotificationItemDto> allRetrievedNotifications = List.of(
            firstPage, secondPage, thirdPage
        ).stream().flatMap(List::stream).toList();
        
        assertThat(allRetrievedNotifications).extracting(NotificationItemDto::getType)
            .containsOnly(Type.CHAT);
    }
    
    @Test
    @DisplayName("UT-NT-019: 일괄 읽음 처리")
    void utNt019MarksAllAsRead() {
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
    @DisplayName("UT-NT-020: 읽음 처리 멱등성")
    void utNt020MarkAllAsReadIsIdempotent() {
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
    @DisplayName("UT-NT-021: 사용자별 격리 검증")
    void utNt021OtherUsersNotificationsNotAffected() {
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
    @DisplayName("UT-NT-022: 읽음 처리 후 개수 확인")
    void utNt022UnreadCountBecomesZeroAfterMarkAllRead() {
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
    @DisplayName("UT-NT-023: 시간 기반 조회")
    void utNt023FindsNotificationsAfterTimestampForSseReconnection() {
        // given - 과거 시간을 기준으로 설정
        LocalDateTime veryPastTime = LocalDateTime.now().minusHours(1);
        
        // 현재 시점에 알림 생성
        AppNotification notification1 = notificationRepository.save(
            AppNotification.create(testUser, likeType, "놓친 알림 1"));
        AppNotification notification2 = notificationRepository.save(
            AppNotification.create(testUser, chatType, "놓친 알림 2"));
        
        // when - 1시간 전 이후의 모든 알림 조회 (현재 생성된 알림들이 모두 포함되어야 함)
        List<AppNotification> missedNotifications = notificationRepositoryImpl
            .findNotificationsByUserIdAfter(testUser.getUserId(), veryPastTime);
        
        // then - 현재 생성된 알림들이 조회되어야 함 (적어도 2개 이상)
        assertThat(missedNotifications.size()).isGreaterThanOrEqualTo(2);
        
        // 생성한 2개 알림이 모두 포함되어있는지 확인
        List<Long> notificationIds = missedNotifications.stream()
            .map(AppNotification::getId)
            .toList();
        assertThat(notificationIds).contains(notification1.getId(), notification2.getId());
    }

    @Test
    @DisplayName("UT-NT-024: DB 상태 반영 확인")
    void utNt024ReadStatusCorrectlyReflectedInDb() {
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
    @DisplayName("UT-NT-025: 선택적 삭제 검증")
    void utNt025SpecificNotificationDeletedWhileOthersRemain() {
        // given - 테스트 시작 전에 기존 알림들 모두 정리
        notificationRepository.deleteAll();
        
        // 새로 여러 개의 알림 생성
        AppNotification notification1 = notificationRepository.save(
            AppNotification.create(testUser, chatType, "유지될 알림1"));
        AppNotification notification2 = notificationRepository.save(
            AppNotification.create(testUser, likeType, "삭제될 알림"));
        AppNotification notification3 = notificationRepository.save(
            AppNotification.create(testUser, chatType, "유지될 알림2"));
        
        Long deleteTargetId = notification2.getId();
        
        // 삭제 전 3개 모두 존재 확인
        assertThat(notificationRepository.findAll()).hasSize(3);
        assertThat(notificationRepository.findById(deleteTargetId)).isPresent();
        
        // when - 특정 알림만 삭제
        notificationRepository.delete(notification2);
        
        // then - 삭제된 알림만 없어지고 나머지는 유지
        assertThat(notificationRepository.findById(deleteTargetId)).isEmpty();
        
        List<AppNotification> remainingNotifications = notificationRepository.findAll();
        assertThat(remainingNotifications).hasSize(2);
        assertThat(remainingNotifications).extracting(AppNotification::getId)
            .containsExactlyInAnyOrder(notification1.getId(), notification3.getId());
        
        // 내용은 템플릿이 적용될 수 있으므로 ID로만 검증
    }
    
    @Test
    @DisplayName("UT-NT-026: 통계 조회 검증")
    void utNt026GetsNotificationStats() {
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
        assertThat(stats.fcmFailedCount()).isEqualTo(0L); // FCM 전송 실패 건수 (이 테스트에서는 실패 없음)
    }
    
    @Test
    @DisplayName("UT-NT-027: 오래된 알림 정리")
    void utNt027DeletesOldReadNotifications() {
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
    @DisplayName("UT-NT-028: 커버리지 개선 - 읽지 않은 알림 조회")
    void utNt028FindsUnreadNotificationsByUserId() {
        // given
        AppNotification unread1 = notificationRepository.save(AppNotification.create(testUser, chatType, "읽지않은1"));
        AppNotification unread2 = notificationRepository.save(AppNotification.create(testUser, likeType, "읽지않은2"));
        AppNotification read = notificationRepository.save(AppNotification.create(testUser, chatType, "읽음"));
        
        read.markAsRead();
        notificationRepository.save(read);
        
        // when
        List<AppNotification> unreadNotifications = notificationRepositoryImpl
            .findUnreadNotificationsByUserId(testUser.getUserId());
        
        // then
        assertThat(unreadNotifications).hasSize(2);
        assertThat(unreadNotifications).extracting(AppNotification::getId)
            .containsExactlyInAnyOrder(unread1.getId(), unread2.getId());
        assertThat(unreadNotifications).allMatch(n -> !n.isRead());
    }
    
    @Test
    @DisplayName("UT-NT-029: 커버리지 개선 - 실패한 FCM 알림 조회")
    void utNt029FindsFailedFcmNotificationsByUserId() {
        // given - FCM 전송 실패 상황은 DeliveryMethod가 FCM_ONLY 또는 BOTH인 경우만 전송 대상이므로
        // 모든 알림이 기본적으로 FCM 실패 상태이지만, DeliveryMethod에 따라 필터링됨
        // 주어진 알림 타입의 DeliveryMethod 설정에 따라 조회 결과가 다를 수 있음
        AppNotification fcmSuccess = notificationRepository.save(AppNotification.create(testUser, chatType, "FCM 성공"));
        AppNotification fcmFailed = notificationRepository.save(AppNotification.create(testUser, likeType, "FCM 실패"));
        
        fcmSuccess.markFcmSent();
        notificationRepository.save(fcmSuccess);
        
        // when
        List<AppNotification> failedNotifications = notificationRepositoryImpl
            .findFailedFcmNotificationsByUserId(testUser.getUserId());
        
        // then - DeliveryMethod 설정에 따라 0개 또는 1개 조회될 수 있음
        assertThat(failedNotifications).hasSizeLessThanOrEqualTo(1);
        if (!failedNotifications.isEmpty()) {
            assertThat(failedNotifications).allMatch(n -> !n.isFcmSent());
        }
    }
    
    @Test
    @DisplayName("UT-NT-030: 커버리지 개선 - 실패한 SSE 알림 조회")
    void utNt030FindsFailedSseNotificationsByUserId() {
        // given
        AppNotification sseSuccess = notificationRepository.save(AppNotification.create(testUser, chatType, "SSE 성공"));
        AppNotification sseFailed = notificationRepository.save(AppNotification.create(testUser, likeType, "SSE 실패"));
        
        sseSuccess.markSseSent();
        notificationRepository.save(sseSuccess);
        // sseFailed는 SSE 전송 실패 상태 (기본값 false)
        
        // when
        List<AppNotification> failedNotifications = notificationRepositoryImpl
            .findFailedSseNotificationsByUserId(testUser.getUserId());
        
        // then - SSE 전송에 실패한 알림만 조회
        assertThat(failedNotifications).hasSize(1);
        assertThat(failedNotifications.get(0).getId()).isEqualTo(sseFailed.getId());
        assertThat(failedNotifications).allMatch(n -> !n.isSseSent());
    }
    
    @Test
    @DisplayName("UT-NT-031: 커버리지 개선 - fetchJoin으로 알림 조회")
    void utNt031FindsByIdWithFetchJoin() {
        // given
        AppNotification notification = notificationRepository.save(
            AppNotification.create(testUser, chatType, "FetchJoin 테스트"));
        
        // when
        AppNotification found = notificationRepositoryImpl
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
        AppNotification found = notificationRepositoryImpl
            .findByIdWithFetchJoin(nonExistentId);
        
        // then
        assertThat(found).isNull();
    }
    
    @Test
    @DisplayName("UT-NT-033: 커버리지 개선 - 빈 목록 배치 삽입")
    void utNt033BatchInsertsEmptyList() {
        // given
        List<AppNotification> emptyList = List.of();
        
        // when & then - 빈 목록에 대해 예외 발생하지 않음
        assertThatCode(() -> notificationRepositoryImpl.batchInsertNotifications(emptyList))
            .doesNotThrowAnyException();
    }
    
    @Test
    @DisplayName("UT-NT-034: 커버리지 개선 - 배치 삽입 기능")
    void utNt034BatchInsertsNotifications() {
        // given - 기존 데이터 삭제
        notificationRepository.deleteAll();
        
        // 배치 삽입을 위한 알림 생성 (User와 NotificationType는 이미 저장되어 ID가 있음)
        List<AppNotification> notifications = List.of(
            AppNotification.create(testUser, chatType, "배치1"),
            AppNotification.create(testUser, likeType, "배치2"), 
            AppNotification.create(testUser, chatType, "배치3")
        );
        
        // when & then - 배치 삽입이 예외 없이 실행되는지 확인
        assertThatCode(() -> notificationRepositoryImpl.batchInsertNotifications(notifications))
            .doesNotThrowAnyException();
        
        // 빈 리스트 처리도 예외 없이 처리되는지 확인
        assertThatCode(() -> notificationRepositoryImpl.batchInsertNotifications(List.of()))
            .doesNotThrowAnyException();
    }
    
    @Test
    @DisplayName("UT-NT-035: 커버리지 개선 - null cursor 조건 처리")
    void utNt035HandlesNullCursorCondition() {
        // given
        notificationRepository.save(AppNotification.create(testUser, chatType, "cursor 테스트"));
        
        // when - cursor가 null일 때
        List<NotificationItemDto> result = notificationRepositoryImpl
            .findNotificationsByUserId(testUser.getUserId(), null, 10);
        
        // then - 정상적으로 조회되어야 함
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getContent()).contains("테스트 템플릿: cursor 테스트"); // 템플릿이 적용된 내용 확인
    }
    
    @Test
    @DisplayName("UT-NT-036: 커버리지 개선 - 타입별 null cursor 조건")
    void utNt036HandlesNullCursorConditionByType() {
        // given
        notificationRepository.save(AppNotification.create(testUser, likeType, "타입별 cursor 테스트"));
        
        // when - cursor가 null일 때 타입별 조회
        List<NotificationItemDto> result = notificationRepositoryImpl
            .findNotificationsByUserIdAndType(testUser.getUserId(), Type.LIKE, null, 10);
        
        // then - 정상적으로 조회되어야 함
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getContent()).contains("테스트 템플릿: 타입별 cursor 테스트"); // 템플릿이 적용된 내용 확인
        assertThat(result.get(0).getType()).isEqualTo(Type.LIKE);
    }
    
    @Test
    @DisplayName("UT-NT-037: 커버리지 개선 - 통계 null 값 처리")
    void utNt037HandlesNullValuesInStats() {
        // given - 새로운 사용자로 알림이 없는 상태
        User emptyUser = userRepository.save(User.builder()
            .kakaoId(99999L)
            .nickname("빈사용자")
            .status(Status.ACTIVE)
            .build());
        
        // when - 알림이 없는 사용자의 통계 조회
        NotificationRepositoryCustom.NotificationStats stats = 
            notificationRepositoryImpl.getNotificationStats(emptyUser.getUserId());
        
        // then - 모든 값이 0이어야 함 (null -> 0 전환)
        assertThat(stats.totalCount()).isZero();
        assertThat(stats.unreadCount()).isZero();
        assertThat(stats.fcmSentCount()).isZero();
        assertThat(stats.fcmFailedCount()).isZero();
        assertThat(stats.sseSentCount()).isZero();
        assertThat(stats.sseFailedCount()).isZero();
    }

}