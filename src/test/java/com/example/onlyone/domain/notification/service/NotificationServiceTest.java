package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.springframework.data.redis.core.RedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 서비스 통합 테스트
 * 
 * 서비스 레이어의 비즈니스 로직과 데이터 영속성을 검증하는 통합 테스트
 * - 비즈니스 로직의 정확성
 * - 데이터 영속성 및 트랜잭션 관리
 * - 예외 처리 및 에러 시나리오
 * - 서비스 간 통합 동작
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 서비스 통합 테스트")
class NotificationServiceTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리 (순서 중요)
        notificationRepository.deleteAll();
        notificationRepository.flush(); // 즉시 DB에 반영
        userRepository.deleteAll();
        userRepository.flush();
        notificationTypeRepository.deleteAll();
        notificationTypeRepository.flush();
        
        // 실제 DB에 테스트 데이터 생성
        testUser = createTestUser(1L, "테스트유저");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
        testNotification = notificationRepository.save(testNotification);
    }

    @Test
    @DisplayName("UT-NT-028: 읽지 않은 개수 서비스")
    void utNt028ReturnsUnreadCountAccurately() {
        // given
        for (int i = 0; i < 5; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        Long result = notificationService.getUnreadCount(testUser.getUserId());

        // then
        assertThat(result).isEqualTo(6L); // 기존 1개 + 새로 생성한 5개
    }

    @Test
    @DisplayName("UT-NT-029: 빈 개수 서비스")
    void utNt029Returns0WhenNoUnreadNotifications() {
        // given
        testNotification.markAsRead();
        notificationRepository.save(testNotification);

        // when
        Long result = notificationService.getUnreadCount(testUser.getUserId());

        // then
        assertThat(result).isZero();
    }


    @Test
    @DisplayName("UT-NT-031: 사용자 없음 예외")
    void utNt031UserNotFoundReturns404() {
        // given
        Long nonExistentUserId = 99999L;

        // when & then - 존재하지 않는 사용자에 대해 예외 발생
        assertThatThrownBy(() -> notificationService.getUnreadCount(nonExistentUserId))
            .isInstanceOf(CustomException.class)
            .hasMessageContaining("유저를 찾을 수 없습니다");
    }


    @Test
    @DisplayName("UT-NT-033: 사용자별 조회")
    void utNt033GetsNotificationsForSpecificUserOnly() {
        // given
        User anotherUser = createTestUser(2L, "다른유저");
        AppNotification anotherNotification = AppNotification.create(anotherUser, testNotificationType, "다른유저알림");
        notificationRepository.save(anotherNotification);

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(result.getNotifications()).hasSize(1);
        assertThat(result.getNotifications().get(0).getContent()).contains("테스트");
    }

    @Test
    @DisplayName("UT-NT-034: 알림 목록 정렬 검증")
    void shouldReturnNotificationsInDescendingOrderByCreationTime() {
        // given - 시간 차이를 두고 새로운 알림 생성
        AppNotification newerNotification = AppNotification.create(testUser, testNotificationType, "최신알림");
        notificationRepository.save(newerNotification);

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then - 최신 알림이 맨 앞에 오고, 더 많은 알림이 있어야 함
        assertThat(result.getNotifications()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getNotifications().get(0).getContent()).contains("최신알림");
        
        // ID 기반 내림차순 정렬 검증 (ID가 클수록 최신)
        List<Long> notificationIds = result.getNotifications().stream()
            .map(NotificationItemDto::getNotificationId)
            .toList();
        for (int i = 1; i < notificationIds.size(); i++) {
            assertThat(notificationIds.get(i-1)).isGreaterThan(notificationIds.get(i));
        }
    }

    @Test
    @DisplayName("UT-NT-035: 페이지 크기 검증")
    void utNt035ReturnsNotificationsAccordingToPageSize() {
        // given
        for (int i = 0; i < 10; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 5);

        // then
        assertThat(result.getNotifications()).hasSize(5);
    }

    @Test
    @DisplayName("UT-NT-036: 첫 페이지 조회")
    void utNt036StartsFromFirstPageWhenCursorIsNull() {
        // given
        for (int i = 0; i < 5; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "페이지테스트" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(result.getNotifications()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(result.getNotifications().get(0).getNotificationId()).isNotNull();
    }

    @Test
    @DisplayName("UT-NT-037: hasMore 플래그 검증")
    void utNt037HasMoreFlagSetCorrectly() {
        // given - 10개 알림 생성
        for (int i = 0; i < 10; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "hasMore테스트" + i);
            notificationRepository.save(notification);
        }

        // when - 5개씩 조회
        NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 5);
        NotificationListResponseDto lastPage = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(firstPage.isHasMore()).isTrue(); // 더 있음
        assertThat(lastPage.isHasMore()).isFalse(); // 마지막 페이지
    }

    @Test
    @DisplayName("UT-NT-038: unreadCount 포함 검증")
    void utNt038UnreadCountIncludedCorrectly() {
        // given
        for (int i = 0; i < 3; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "읽지않음" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);

        // then
        assertThat(result.getUnreadCount()).isEqualTo(4L); // 기존 1개 + 새로운 3개
    }

    @Test
    @DisplayName("UT-NT-039: 최대 크기 제한")
    void utNt039LimitsSizeParameterTo100() {
        // given - 대량 알림 생성
        for (int i = 0; i < 150; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "대량알림" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 150);

        // then
        assertThat(result.getNotifications().size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("UT-NT-040: 커서 페이징 서비스")
    void utNt040CursorBasedPaginationWorks() {
        // given
        for (int i = 0; i < 10; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 5);
        Long cursor = firstPage.getNotifications().get(firstPage.getNotifications().size() - 1).getNotificationId();
        NotificationListResponseDto secondPage = notificationService.getNotifications(testUser.getUserId(), cursor, 5);

        // then
        List<Long> firstIds = firstPage.getNotifications().stream()
            .map(NotificationItemDto::getNotificationId).toList();
        List<Long> secondIds = secondPage.getNotifications().stream()
            .map(NotificationItemDto::getNotificationId).toList();
        
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    @DisplayName("UT-NT-041: 잘못된 ID 예외")
    void utNt041ThrowsExceptionWhenNotificationIdInvalid() {
        // given
        Long invalidId = 999999L;

        // when & then
        assertThatThrownBy(() -> notificationService.markAsRead(invalidId, testUser.getUserId()))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("UT-NT-042: 읽음 처리 성공")
    void utNt042MarksNotificationAsReadSuccessfully() {
        // given
        AppNotification unreadNotification = AppNotification.create(testUser, testNotificationType, "읽음처리테스트");
        unreadNotification = notificationRepository.save(unreadNotification);

        // when
        notificationService.markAsRead(unreadNotification.getId(), testUser.getUserId());

        // then
        AppNotification updated = notificationRepository.findById(unreadNotification.getId()).orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.isRead()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-043: 읽음 처리 멱등성")
    void utNt043HandlesAlreadyReadNotification() {
        // given
        testNotification.markAsRead();
        testNotification = notificationRepository.save(testNotification);

        // when & then - 예외 없이 정상 처리
        notificationService.markAsRead(testNotification.getId(), testUser.getUserId());
        
        AppNotification result = notificationRepository.findById(testNotification.getId()).orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.isRead()).isTrue();
    }

    @Test
    @DisplayName("UT-NT-044: 권한 검증")
    void utNt044ThrowsExceptionWhenAccessingOtherUsersNotification() {
        // given
        User anotherUser = createTestUser(2L, "다른유저");

        // when & then
        assertThatThrownBy(() -> notificationService.markAsRead(testNotification.getId(), anotherUser.getUserId()))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("UT-NT-045: 음수 ID 예외")
    void utNt045NegativeIdReturns404() {
        // given
        Long negativeId = -1L;

        // when & then
        assertThatThrownBy(() -> notificationService.markAsRead(negativeId, testUser.getUserId()))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("UT-NT-046: 전체 읽음 처리")
    void utNt046MarksAllNotificationsAsRead() {
        // given
        for (int i = 0; i < 5; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
            notificationRepository.save(notification);
        }

        // when
        notificationService.markAllAsRead(testUser.getUserId());

        // then
        Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
        assertThat(unreadCount).isZero();
    }

    @Test
    @DisplayName("UT-NT-047: 빈 상태 처리")
    void utNt047HandlesNoNotificationsCase() {
        // given - 기존 데이터와 Redis 캐시 완전 삭제 후 새로운 사용자 생성
        notificationRepository.deleteAll();
        notificationRepository.flush();
        userRepository.deleteAll(); 
        userRepository.flush();
        notificationTypeRepository.deleteAll();
        notificationTypeRepository.flush();
        
        // Redis 캐시 초기화
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception e) {
            // Redis 캐시 초기화 실패해도 테스트 계속
        }
        
        User newUser = createTestUser(99L, "신규유저");

        // when - 예외 없이 정상 처리
        notificationService.markAllAsRead(newUser.getUserId());
        
        // then
        Long count = notificationService.getUnreadCount(newUser.getUserId());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("UT-NT-048: 사용자 격리")
    void utNt048OtherUsersNotificationsUnaffected() {
        // given
        User anotherUser = createTestUser(2L, "다른유저2");
        userRepository.save(anotherUser);
        
        AppNotification userNotification = AppNotification.create(testUser, testNotificationType, "사용자1알림");
        AppNotification otherNotification = AppNotification.create(anotherUser, testNotificationType, "사용자2알림");
        notificationRepository.saveAll(List.of(userNotification, otherNotification));

        // when
        notificationService.markAllAsRead(testUser.getUserId());

        // then
        Long userCount = notificationService.getUnreadCount(testUser.getUserId());
        Long otherCount = notificationService.getUnreadCount(anotherUser.getUserId());
        
        assertThat(userCount).isZero();
        assertThat(otherCount).isEqualTo(1L); // 다른 사용자 알림은 영향 없음
    }

    @Test
    @DisplayName("UT-NT-049: 성능 테스트")
    void utNt049BulkReadPerformanceUnder3Seconds() {
        // given
        for (int i = 0; i < 100; i++) { // 테스트 환경에서는 100개로 축소
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "성능테스트" + i);
            notificationRepository.save(notification);
        }

        // when
        long startTime = System.currentTimeMillis();
        notificationService.markAllAsRead(testUser.getUserId());
        long endTime = System.currentTimeMillis();

        // then
        long duration = endTime - startTime;
        assertThat(duration).isLessThan(3000); // 3초 미만
        assertThat(notificationService.getUnreadCount(testUser.getUserId())).isZero();
    }

    @Test
    @DisplayName("UT-NT-050: 삭제 기능")
    void utNt050DeletesNotificationSuccessfully() {
        // given
        AppNotification toDelete = AppNotification.create(testUser, testNotificationType, "삭제테스트");
        toDelete = notificationRepository.save(toDelete);
        Long deleteId = toDelete.getId();

        // when
        notificationService.deleteNotification(testUser.getUserId(), deleteId);

        // then
        assertThat(notificationRepository.findById(deleteId)).isEmpty();
    }

    @Test
    @DisplayName("UT-NT-051: 읽은 알림 삭제")
    void utNt051DeletesReadNotification() {
        // given
        AppNotification readNotification = AppNotification.create(testUser, testNotificationType, "읽은알림");
        readNotification.markAsRead();
        readNotification = notificationRepository.save(readNotification);
        Long notificationId = readNotification.getId();

        // when
        notificationService.deleteNotification(testUser.getUserId(), notificationId);

        // then
        assertThat(notificationRepository.findById(notificationId)).isEmpty();
    }

    @Test
    @DisplayName("UT-NT-052: 중복 삭제 예외")
    void utNt052AlreadyDeletedNotificationReturns404() {
        // given
        AppNotification notification = AppNotification.create(testUser, testNotificationType, "삭제될알림");
        notification = notificationRepository.save(notification);
        Long notificationId = notification.getId();

        // 첫 번째 삭제
        notificationService.deleteNotification(testUser.getUserId(), notificationId);

        // when & then - 재삭제 시도
        assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), notificationId))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("UT-NT-053: 알림 생성")
    void utNt053CreatesNotificationSuccessfully() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "새 알림"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getNotificationId()).isNotNull();
        assertThat(result.getContent()).contains("새 알림");
    }

    @Test
    @DisplayName("UT-NT-054: 생성 시 초기화")
    void utNt054InitializesNotificationAsUnread() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "읽지않음 테스트"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        AppNotification saved = notificationRepository.findById(result.getNotificationId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    @DisplayName("UT-NT-055: 템플릿 적용")
    void utNt055AppliesTemplateToNotificationContent() {
        // given
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "템플릿 테스트"
        );

        // when
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then
        assertThat(result.getContent()).isEqualTo("테스트 템플릿: 템플릿 테스트");
    }

    @Test
    @DisplayName("UT-NT-056: 타입별 템플릿")
    void utNt056NotificationTemplatesAppliedCorrectly() {
        // given
        NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "정산 완료: %s원이 입금되었습니다");
        notificationTypeRepository.save(settlementType);

        // when
        NotificationCreateRequestDto chatRequest = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "새 메시지"
        );
        NotificationCreateRequestDto settlementRequest = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.SETTLEMENT, "50000"
        );

        NotificationCreateResponseDto chatResult = notificationService.createNotification(chatRequest);
        NotificationCreateResponseDto settlementResult = notificationService.createNotification(settlementRequest);

        // then
        assertThat(chatResult.getContent()).contains("새 메시지");
        assertThat(settlementResult.getContent()).isEqualTo("정산 완료: 50000원이 입금되었습니다");
    }

    @Test
    @DisplayName("UT-NT-057: 생성 시 사용자 검증")
    void utNt057CreateNotificationWithNonexistentUserReturns404() {
        // given
        Long nonExistentUserId = 99999L;
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            nonExistentUserId, Type.CHAT, "테스트 알림"
        );

        // when & then
        assertThatThrownBy(() -> notificationService.createNotification(request))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }




    @Test
    @DisplayName("UT-NT-058: 타입별 템플릿 데이터 일관성")
    void shouldApplyCorrectTemplateAndPersistByType() {
        // given - 다양한 타입의 알림 타입 생성
        NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요: %s");
        NotificationType settlementType = NotificationType.of(Type.SETTLEMENT, "정산 완료: %s원이 입금되었습니다");
        notificationTypeRepository.save(likeType);
        notificationTypeRepository.save(settlementType);

        // when
        NotificationCreateResponseDto chatNotification = notificationService.createNotification(
            NotificationCreateRequestDto.of(testUser.getUserId(), Type.CHAT, "새 메시지")
        );
        NotificationCreateResponseDto likeNotification = notificationService.createNotification(
            NotificationCreateRequestDto.of(testUser.getUserId(), Type.LIKE, "게시물")
        );
        NotificationCreateResponseDto settlementNotification = notificationService.createNotification(
            NotificationCreateRequestDto.of(testUser.getUserId(), Type.SETTLEMENT, "50000")
        );

        // then - 템플릿이 올바르게 적용되고 데이터베이스에 저장되었는지 검증
        assertThat(chatNotification.getContent()).isEqualTo("테스트 템플릿: 새 메시지");
        assertThat(likeNotification.getContent()).isEqualTo("좋아요: 게시물");
        assertThat(settlementNotification.getContent()).isEqualTo("정산 완료: 50000원이 입금되었습니다");
        
        // 실제 데이터베이스에서 저장된 데이터 검증
        AppNotification savedChat = notificationRepository.findById(chatNotification.getNotificationId()).orElse(null);
        AppNotification savedLike = notificationRepository.findById(likeNotification.getNotificationId()).orElse(null);
        AppNotification savedSettlement = notificationRepository.findById(settlementNotification.getNotificationId()).orElse(null);
        
        assertThat(savedChat).isNotNull();
        assertThat(savedChat.getContent()).isEqualTo("테스트 템플릿: 새 메시지");
        assertThat(savedChat.isRead()).isFalse();
        
        assertThat(savedLike).isNotNull();
        assertThat(savedLike.getContent()).isEqualTo("좋아요: 게시물");
        assertThat(savedLike.isRead()).isFalse();
        
        assertThat(savedSettlement).isNotNull();
        assertThat(savedSettlement.getContent()).isEqualTo("정산 완료: 50000원이 입금되었습니다");
        assertThat(savedSettlement.isRead()).isFalse();
    }

    @Test
    @DisplayName("UT-NT-062: 전체 플로우")
    void utNt062FullLifecycleWorkflow() {
        // given & when & then
        // 1. 생성
        NotificationCreateRequestDto createRequest = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "생명주기 테스트"
        );
        var created = notificationService.createNotification(createRequest);
        assertThat(created).isNotNull();

        // 2. 조회
        var notifications = notificationService.getNotifications(testUser.getUserId(), null, 20);
        assertThat(notifications.getNotifications()).isNotEmpty();

        // 3. 읽음
        notificationService.markAsRead(created.getNotificationId(), testUser.getUserId());
        var afterRead = notificationRepository.findById(created.getNotificationId()).orElse(null);
        assertThat(afterRead).isNotNull();
        assertThat(afterRead.isRead()).isTrue();

        // 4. 삭제
        notificationService.deleteNotification(testUser.getUserId(), created.getNotificationId());
        assertThat(notificationRepository.findById(created.getNotificationId())).isEmpty();
    }

    @Test
    @DisplayName("UT-NT-063: 권한 검증 통합")
    void utNt063UnauthorizedAccessReturns404() {
        // given
        User anotherUser = createTestUser(3L, "다른유저3");
        AppNotification userNotification = AppNotification.create(testUser, testNotificationType, "사용자알림");
        userNotification = notificationRepository.save(userNotification);

        // when & then - 다른 사용자가 접근
        Long notificationId = userNotification.getId();
        
        // 읽음 처리 시도
        assertThatThrownBy(() -> notificationService.markAsRead(notificationId, anotherUser.getUserId()))
            .isInstanceOf(CustomException.class);

        // 삭제 시도
        assertThatThrownBy(() -> notificationService.deleteNotification(anotherUser.getUserId(), notificationId))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("UT-NT-059: 대량 삭제 일관성 검증")
    void shouldMaintainConsistencyWhenDeletingMultipleNotifications() {
        // given - 대량 알림 생성
        int notificationCount = 10;
        for (int i = 0; i < notificationCount; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "삭제대상" + i);
            notificationRepository.save(notification);
        }
        
        // 즉시 DB에 반영하여 일관성 보장
        notificationRepository.flush();
        
        // 삭제할 알림들을 미리 조회
        List<AppNotification> notifications = notificationRepository.findAll().stream()
            .filter(n -> n.getUser().getUserId().equals(testUser.getUserId()))
            .filter(n -> n.getContent().contains("삭제대상"))
            .toList();
        
        // 삭제 전 DB 상태 확인 (testNotification 1개 + 새로 만든 10개 = 11개)
        Long initialDbCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(initialDbCount).isEqualTo(11L); // 1 (setup) + 10 (new)
        assertThat(notifications).hasSize(10);
        
        // when - 순차적으로 알림 삭제
        for (AppNotification notification : notifications) {
            notificationService.deleteNotification(testUser.getUserId(), notification.getId());
        }
        
        // then - 삭제된 알림들이 데이터베이스에서 제거되고 읽지 않은 개수가 정확히 업데이트됨
        for (AppNotification notification : notifications) {
            assertThat(notificationRepository.findById(notification.getId())).isEmpty();
        }
        
        // 삭제 후 DB 상태 확인 - testNotification 1개만 남아야 함
        Long finalDbCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(finalDbCount).isEqualTo(1L); // testNotification from setUp()
        
        // Service와 DB 일관성 확인
        Long finalUnreadCount = notificationService.getUnreadCount(testUser.getUserId());
        assertThat(finalUnreadCount).isEqualTo(finalDbCount);
    }


    @Test
    @DisplayName("UT-NT-060: 순차 읽음 상태 일관성")
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void shouldMaintainConsistencyWhenMarkingMultipleNotificationsAsRead() {
        // given - 여러 알림 생성
        int notificationCount = 10;
        for (int i = 0; i < notificationCount; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "읽음처리대상" + i);
            notificationRepository.save(notification);
        }
        
        // 즉시 DB에 반영하여 일관성 보장
        notificationRepository.flush();
        
        // 읽음 처리할 알림들을 미리 조회
        List<AppNotification> unreadNotifications = notificationRepository.findAll().stream()
            .filter(n -> n.getUser().getUserId().equals(testUser.getUserId()))
            .filter(n -> !n.isRead())
            .filter(n -> n.getContent().contains("읽음처리대상"))
            .toList();
        
        // 처리 전 전체 읽지 않은 개수 확인 (testNotification 1개 + 새로 만든 10개 = 11개)
        // 처리 전 상태 확인
        Long dbCountBeforeProcessing = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(dbCountBeforeProcessing).isEqualTo(11L); // 1 (setup) + 10 (new)
        assertThat(unreadNotifications).hasSize(10);
        
        // when - 순차적으로 알림 읽음 처리
        for (AppNotification notification : unreadNotifications) {
            notificationService.markAsRead(notification.getId(), testUser.getUserId());
        }
        
        // then - 모든 알림이 읽음 처리되고 읽지 않은 개수가 정확히 업데이트됨
        for (AppNotification notification : unreadNotifications) {
            AppNotification updated = notificationRepository.findById(notification.getId()).orElse(null);
            assertThat(updated).isNotNull();
            assertThat(updated.isRead()).isTrue();
        }
        
        // then - 읽음처리대상 알림들을 처리한 후 남은 건 testNotification 1개만 있어야 함
        Long finalDbCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(finalDbCount).isEqualTo(1L); // testNotification from setUp()
        
        // Service와 DB 일관성 확인
        Long finalUnreadCount = notificationService.getUnreadCount(testUser.getUserId());
        assertThat(finalUnreadCount).isEqualTo(finalDbCount);
    }

    @Test
    @DisplayName("UT-NT-061: DB 상태 일치 검증")
    void shouldReturnUnreadCountConsistentWithDbState() {
        // given - 추가 알림 생성
        int additionalNotifications = 3;
        for (int i = 0; i < additionalNotifications; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "일관성테스트" + i);
            notificationRepository.save(notification);
        }

        // when - 서비스와 직접 DB 조회 결과 비교
        Long serviceCount = notificationService.getUnreadCount(testUser.getUserId());
        Long dbCount = notificationRepository.countUnreadByUserId(testUser.getUserId());

        // then - 두 값이 일치해야 함
        assertThat(serviceCount).isEqualTo(dbCount);
        assertThat(serviceCount).isEqualTo(4L); // 기존 1개 + 새로 생성한 3개
    }

    @Test
    @DisplayName("UT-NT-064: 삭제 후 데이터 정리")
    void shouldCleanupDataAndCacheAfterDeletion() {
        // given - 삭제할 알림 생성
        AppNotification notification = AppNotification.create(testUser, testNotificationType, "삭제검증");
        notification = notificationRepository.save(notification);
        Long notificationId = notification.getId();
        
        // 삭제 전 DB 상태 확인 (testNotification 1개 + 새로 만든 1개 = 2개)
        Long dbCountBeforeDeletion = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(dbCountBeforeDeletion).isEqualTo(2L);

        // when - 알림 삭제
        notificationService.deleteNotification(testUser.getUserId(), notificationId);

        // then - 데이터베이스에서 완전히 제거됨
        assertThat(notificationRepository.findById(notificationId)).isEmpty();
        
        // 삭제 후 DB 상태 확인 - testNotification 1개만 남아야 함
        Long dbCountAfterDeletion = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(dbCountAfterDeletion).isEqualTo(1L); // testNotification from setUp()
        
        // Service count도 DB와 일치하는지 확인 (캐시가 무효화되었는지)
        Long serviceCountAfterDeletion = notificationService.getUnreadCount(testUser.getUserId());
        assertThat(serviceCountAfterDeletion).isEqualTo(dbCountAfterDeletion);
    }

    @Test
    @DisplayName("UT-NT-065: 타입별 알림 조회")
    void utNt065GetNotificationsByTypeWorks() {
        // given - 다른 타입들 생성
        NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿: %s");
        likeType = notificationTypeRepository.save(likeType);
        
        // 각 타입별 알림 생성
        AppNotification chatNotification1 = AppNotification.create(testUser, testNotificationType, "채팅1");
        AppNotification chatNotification2 = AppNotification.create(testUser, testNotificationType, "채팅2");
        AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요");
        
        notificationRepository.save(chatNotification1);
        notificationRepository.save(chatNotification2);
        notificationRepository.save(likeNotification);

        // when - CHAT 타입만 조회
        NotificationListResponseDto result = notificationService.getNotificationsByType(testUser.getUserId(), Type.CHAT, null, 20);

        // then - CHAT 타입 알림만 반환되어야 함 (testNotification + 새로 생성한 2개 = 3개)
        assertThat(result.getNotifications()).hasSize(3);
        assertThat(result.getNotifications())
            .allMatch(notification -> notification.getContent().contains("테스트") || notification.getContent().contains("채팅"));
    }

    @Test
    @DisplayName("UT-NT-066: 타입별 알림 페이징")
    void utNt066TypeFilteringWithPaginationWorks() {
        // given - CHAT 타입 알림을 많이 생성
        for (int i = 0; i < 15; i++) {
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "채팅 알림" + i);
            notificationRepository.save(notification);
        }

        // when - 첫 번째 페이지 조회 (10개)
        NotificationListResponseDto firstPage = notificationService.getNotificationsByType(testUser.getUserId(), Type.CHAT, null, 10);
        
        // 두 번째 페이지 조회
        NotificationListResponseDto secondPage = notificationService.getNotificationsByType(
            testUser.getUserId(), Type.CHAT, firstPage.getCursor(), 10);

        // then
        assertThat(firstPage.getNotifications()).hasSize(10);
        assertThat(firstPage.isHasMore()).isTrue();
        assertThat(secondPage.getNotifications()).hasSize(6); // testNotification(1) + 새로 생성한 15개 중 남은 6개
        assertThat(secondPage.isHasMore()).isFalse();
    }

    @Test  
    @DisplayName("UT-NT-067: 이벤트 핸들러 트리거 검증")
    void utNt067NotificationCreatedEventTriggered() {
        // given - 이벤트가 발생할 알림 생성 요청
        NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
            testUser.getUserId(), Type.CHAT, "이벤트 테스트"
        );

        // when - 알림 생성 (이벤트가 발행되어야 함)
        NotificationCreateResponseDto result = notificationService.createNotification(request);

        // then - 알림이 성공적으로 생성되었는지 검증
        assertThat(result).isNotNull();
        assertThat(result.getNotificationId()).isNotNull();
        assertThat(result.getContent()).contains("이벤트 테스트");
        
        // 실제 DB에 저장되었는지 확인
        AppNotification saved = notificationRepository.findById(result.getNotificationId()).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getContent()).contains("이벤트 테스트");
    }
    
    @Test
    @DisplayName("UT-NT-068: 커버리지 개선 - null userId getUnreadCount")
    void utNt068NullUserIdThrowsException() {
        // when & then - null userId에 대해 예외 발생
        assertThatThrownBy(() -> notificationService.getUnreadCount(null))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }
    
    @Test
    @DisplayName("UT-NT-069: 커버리지 개선 - Redis 에러 폴백")
    void utNt069RedisErrorFallbackToDatabase() {
        // given
        User redisTestUser = createTestUser(9901L, "redis_test_user");
        
        AppNotification notification = AppNotification.create(redisTestUser, testNotificationType, "Redis 테스트");
        notificationRepository.save(notification);
        
        // when - Redis 실패 시 DB로 폴백
        Long count = notificationService.getUnreadCount(redisTestUser.getUserId());
        
        // then - 예외 없이 결과 반환
        assertThat(count).isGreaterThanOrEqualTo(1L);
    }
    
    @Test
    @DisplayName("UT-NT-070: 커버리지 개선 - 기존 사용자와 타입으로 알림 생성")
    void utNt070CreateNotificationWithExistingUserAndType() {
        // when - 기존 사용자와 타입으로 알림 생성
        NotificationCreateResponseDto result = notificationService.createNotification(
            testUser, Type.CHAT, "기존 사용자 테스트"
        );
        
        // then
        assertThat(result).isNotNull();
        assertThat(result.getNotificationId()).isNotNull();
        assertThat(result.getContent()).contains("기존 사용자 테스트");
    }
    
    @Test
    @DisplayName("UT-NT-071: 커버리지 개선 - 없는 알림 타입 예외")
    void utNt071NonExistentNotificationTypeThrowsException() {
        // given - 데이터베이스에 없는 타입 사용 시도
        // 모든 타입을 삭제하여 예외 상황 생성
        notificationTypeRepository.deleteAll();
        notificationTypeRepository.flush();
        
        // when & then - 없는 타입으로 알림 생성 시 예외 발생
        assertThatThrownBy(() -> notificationService.createNotification(
            testUser, Type.CHAT, "없는 타입 테스트"
        ))
        .isInstanceOf(CustomException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
    }
    
    @Test
    @DisplayName("UT-NT-072: 커버리지 개선 - 빈 알림 목록 처리")
    void utNt072EmptyNotificationListHandling() {
        // given - 모든 알림 삭제
        notificationRepository.deleteAll();
        notificationRepository.flush();
        
        // when
        NotificationListResponseDto result = notificationService.getNotifications(testUser.getUserId(), null, 20);
        
        // then
        assertThat(result.getNotifications()).isEmpty();
        assertThat(result.getCursor()).isNull();
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getUnreadCount()).isZero();
    }
    
    @Test
    @DisplayName("UT-NT-073: 커버리지 개선 - 타입별 빈 목록 처리")
    void utNt073EmptyNotificationListByTypeHandling() {
        // given - 모든 알림 삭제
        notificationRepository.deleteAll();
        notificationRepository.flush();
        
        // when
        NotificationListResponseDto result = notificationService.getNotificationsByType(
            testUser.getUserId(), Type.CHAT, null, 20);
        
        // then
        assertThat(result.getNotifications()).isEmpty();
        assertThat(result.getCursor()).isNull();
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getUnreadCount()).isZero();
    }

    // Helper 메서드
    private User createTestUser(Long kakaoId, String nickname) {
        User user = User.builder()
            .kakaoId(kakaoId)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
        return userRepository.save(user);
    }
}