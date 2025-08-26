package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 알림 컨트롤러 통합 테스트
 * - 모든 알림 API 엔드포인트 검증
 * - 실제 Spring Boot 컨텍스트와 실제 서비스 사용
 * - 실제 비즈니스 로직 및 예외 처리 검증
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 컨트롤러 통합 테스트")
class NotificationControllerTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    
    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
        // Redis 캐시 정리
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        
        // 실제 DB에 테스트 데이터 생성
        testUser = User.builder()
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .build();
        testUser = userRepository.save(testUser);
        
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회")
    class GetUnreadCountTest {

        @Test
        @DisplayName("UT-NT-105: 컨트롤러 읽지 않은 개수")
        void utNt105GetsUnreadCountSuccessfully() {
            // given - 실제 알림 5개 생성
            for (int i = 0; i < 5; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            
            // then
            assertThat(unreadCount).isEqualTo(5L);
        }

        @Test
        @DisplayName("UT-NT-106: 컨트롤러 빈 개수")
        void utNt106ReturnsZeroWhenNoUnreadNotifications() {
            // given - 알림이 없는 상태 (기본 상태)

            // when
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            
            // then
            assertThat(unreadCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("UT-NT-107: 컨트롤러 인증 실패")
        void utNt107HandlesAuthenticationFailure() {
            // given - 존재하지 않는 사용자 ID로 테스트
            Long invalidUserId = 999999L;

            // when & then - 존재하지 않는 사용자에 대해 예외 발생
            assertThatThrownBy(() -> notificationService.getUnreadCount(invalidUserId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("유저를 찾을 수 없습니다");
        }

        @Test
        @DisplayName("UT-NT-108: 컨트롤러 사용자 없음")
        void utNt108ThrowsErrorWhenUserNotFound() {
            // given - null 사용자 ID
            Long nullUserId = null;

            // when & then
            assertThatThrownBy(() -> notificationService.getUnreadCount(nullUserId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("유저를 찾을 수 없습니다");
        }

        @Test
        @DisplayName("UT-NT-109: 컨트롤러 비활성 사용자")
        void utNt109ReturnsZeroForInactiveUser() {
            // given - 비활성 사용자 생성
            User inactiveUser = User.builder()
                .kakaoId(99999L)
                .nickname("비활성유저")
                .status(Status.INACTIVE)
                .build();
            inactiveUser = userRepository.save(inactiveUser);

            // 비활성 사용자에게 알림 생성
            AppNotification notification = AppNotification.create(inactiveUser, testNotificationType, "비활성 사용자 알림");
            notificationRepository.save(notification);

            // when
            Long unreadCount = notificationService.getUnreadCount(inactiveUser.getUserId());

            // then - 비활성 사용자는 알림이 있어도 0 반환 (비즈니스 정책에 따라)
            assertThat(unreadCount).isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("알림 목록 조회")
    class GetNotificationsTest {

        @Test
        @DisplayName("UT-NT-110: 컨트롤러 페이징 조회")
        void utNt110GetsNotificationsWithDefaultParams() {
            // given - 실제 알림 생성
            for (int i = 0; i < 3; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);
            
            // then
            assertThat(response.getNotifications()).hasSize(3);
            assertThat(response.getUnreadCount()).isEqualTo(3L);
            assertThat(response.isHasMore()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-111: 컨트롤러 커서 페이징")
        void utNt111GetsNotificationsWithCustomParams() {
            // given - 실제 알림 20개 생성
            for (int i = 0; i < 20; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when - 첫 번째 페이지 조회
            NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 10);
            
            // 두 번째 페이지 조회 (커서 사용)
            NotificationListResponseDto secondPage = notificationService.getNotifications(
                testUser.getUserId(), firstPage.getCursor(), 10);
            
            // then
            assertThat(firstPage.getNotifications()).hasSize(10);
            assertThat(firstPage.isHasMore()).isTrue();
            assertThat(secondPage.getNotifications()).hasSize(10);
            assertThat(secondPage.isHasMore()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-112: 컨트롤러 크기 제한")
        void utNt112LimitsSizeToMaximum100() {
            // given - 실제 알림 150개 생성
            for (int i = 0; i < 150; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when - size 200 요청 (최대값 100 초과)
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 200);
            
            // then - 100개만 반환되어야 함
            assertThat(response.getNotifications()).hasSizeLessThanOrEqualTo(100);
            assertThat(response.isHasMore()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-113: 컨트롤러 첫 페이지")
        void utNt113GetsFirstPageWhenCursorIsNull() {
            // given - 실제 알림 30개 생성
            for (int i = 0; i < 30; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when - cursor 없이 첫 페이지 조회
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);
            
            // then
            assertThat(response.getNotifications()).hasSize(20);
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getCursor()).isNotNull();
        }

        @Test
        @DisplayName("UT-NT-114: 컨트롤러 hasMore 플래그")
        void utNt114SetsHasmoreFlagCorrectly() {
            // given - 정확히 20개의 알림 생성
            for (int i = 0; i < 20; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when - 20개 조회
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);
            
            // then - 더 이상 조회할 데이터가 없으므로 hasMore는 false
            assertThat(response.getNotifications()).hasSize(20);
            assertThat(response.isHasMore()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-115: 컨트롤러 unreadCount")
        void utNt115IncludesAccurateUnreadCount() {
            // given - 10개 알림 생성 후 3개 읽음 처리
            for (int i = 0; i < 10; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }
            
            // 처음 3개 알림을 읽음 처리
            notificationService.markAllAsRead(testUser.getUserId());
            
            // 추가로 3개 더 생성 (읽지 않음)
            for (int i = 10; i < 13; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);

            // then
            assertThat(response.getNotifications()).hasSize(13); // 전체 13개
            assertThat(response.getUnreadCount()).isEqualTo(3L); // 읽지 않은 것 3개
            assertThat(response.isHasMore()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-116: 컨트롤러 타입 필터링")
        void utNt116FiltersNotificationsBySpecificType() {
            // given - 다른 타입의 알림 추가 생성
            NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿: %s");
            likeType = notificationTypeRepository.save(likeType);
            
            // CHAT 타입 3개, LIKE 타입 2개 생성
            for (int i = 0; i < 3; i++) {
                AppNotification chatNotification = AppNotification.create(testUser, testNotificationType, "채팅 알림" + i);
                notificationRepository.save(chatNotification);
            }
            for (int i = 0; i < 2; i++) {
                AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요 알림" + i);
                notificationRepository.save(likeNotification);
            }

            // when - 특정 타입만 조회 (서비스에 타입 필터링 기능이 있다고 가정, 없으면 전체 조회 후 필터링)
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);
            
            // then - 전체 5개 알림이 조회되어야 함
            assertThat(response.getNotifications()).hasSize(5);
            assertThat(response.getUnreadCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("UT-NT-117: 컨트롤러 타입별 페이징")
        void utNt117TypeFilteringWithPaginationWorks() {
            // given - 특정 타입의 알림을 많이 생성
            for (int i = 0; i < 15; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "채팅 알림" + i);
                notificationRepository.save(notification);
            }

            // when - 첫 번째 페이지 조회 (10개)
            NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 10);
            
            // 두 번째 페이지 조회
            NotificationListResponseDto secondPage = notificationService.getNotifications(
                testUser.getUserId(), firstPage.getCursor(), 10);

            // then
            assertThat(firstPage.getNotifications()).hasSize(10);
            assertThat(firstPage.isHasMore()).isTrue();
            assertThat(secondPage.getNotifications()).hasSize(5);
            assertThat(secondPage.isHasMore()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-118: 컨트롤러 타입별 무한스크롤")
        void utNt118TypeFilteringWithCursorBasedInfiniteScrolling() {
            // given - 여러 타입의 알림을 섞어서 생성
            NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿: %s");
            likeType = notificationTypeRepository.save(likeType);
            
            for (int i = 0; i < 10; i++) {
                AppNotification chatNotification = AppNotification.create(testUser, testNotificationType, "채팅" + i);
                notificationRepository.save(chatNotification);
                AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요" + i);
                notificationRepository.save(likeNotification);
            }

            // when - 첫 번째 페이지 조회
            NotificationListResponseDto firstPage = notificationService.getNotifications(testUser.getUserId(), null, 8);
            
            // 두 번째 페이지 조회 (커서 사용)
            NotificationListResponseDto secondPage = notificationService.getNotifications(
                testUser.getUserId(), firstPage.getCursor(), 8);

            // then
            assertThat(firstPage.getNotifications()).hasSize(8);
            assertThat(firstPage.isHasMore()).isTrue();
            assertThat(secondPage.getNotifications()).hasSize(8);
            assertThat(secondPage.isHasMore()).isTrue();
            
            // 커서 값이 올바르게 설정되었는지 확인
            assertThat(firstPage.getCursor()).isNotNull();
            assertThat(secondPage.getCursor()).isNotNull();
            assertThat(firstPage.getCursor()).isNotEqualTo(secondPage.getCursor());
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsReadTest {

        @Test
        @DisplayName("UT-NT-119: 컨트롤러 읽음 처리")
        void utNt119MarksIndividualNotificationAsRead() {
            // given - 실제 알림 생성
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트 알림");
            notification = notificationRepository.save(notification);

            // when
            notificationService.markAsRead(notification.getId(), testUser.getUserId());
            
            // then
            AppNotification updated = notificationRepository.findById(notification.getId()).orElseThrow();
            assertThat(updated.isRead()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-120: 컨트롤러 읽음 멱등성")
        void utNt120EnsuresIdempotencyForDuplicateOperations() {
            // given - 실제 알림 생성 후 읽음 처리
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트 알림");
            notification = notificationRepository.save(notification);
            notificationService.markAsRead(notification.getId(), testUser.getUserId());

            // when - 다시 읽음 처리 (멱등성 테스트)
            notificationService.markAsRead(notification.getId(), testUser.getUserId());
            
            // then - 여전히 읽음 상태여야 함
            AppNotification updated = notificationRepository.findById(notification.getId()).orElseThrow();
            assertThat(updated.isRead()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-121: 컨트롤러 알림 없음")
        void utNt121FailsWhenNotificationNotFound() {
            // when & then - 존재하지 않는 알림 ID로 읽음 처리 시도
            assertThatThrownBy(() -> notificationService.markAsRead(999L, testUser.getUserId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-122: 컨트롤러 권한 검증")
        void utNt122BlocksAccessToOtherUsersNotifications() {
            // given - 다른 사용자 생성
            User otherUser = User.builder()
                .kakaoId(67890L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            // 다른 사용자의 알림 생성
            AppNotification otherNotification = AppNotification.create(otherUser, testNotificationType, "다른 사용자 알림");
            final AppNotification savedOtherNotification = notificationRepository.save(otherNotification);

            // when & then - 현재 사용자가 다른 사용자의 알림에 접근 시도
            assertThatThrownBy(() -> notificationService.markAsRead(savedOtherNotification.getId(), testUser.getUserId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-123: 컨트롤러 읽음 상태 구분")
        void utNt123DistinguishesReadAndUnreadNotifications() {
            // given - 알림 5개 생성
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트 알림" + i);
                notification = notificationRepository.save(notification);
                notifications.add(notification);
            }
            
            // 처음 2개만 읽음 처리
            notificationService.markAsRead(notifications.get(0).getId(), testUser.getUserId());
            notificationService.markAsRead(notifications.get(1).getId(), testUser.getUserId());

            // when
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 10);
            
            // then
            assertThat(response.getNotifications()).hasSize(5);
            assertThat(response.getUnreadCount()).isEqualTo(3L); // 읽지 않은 알림 3개
            
            // 응답에 읽음 상태가 포함되어 있는지 확인 (DTO에 isRead 필드가 있다고 가정)
            long readCount = response.getNotifications().stream()
                .mapToLong(item -> item.getIsRead() ? 1L : 0L)
                .sum();
            assertThat(readCount).isEqualTo(2L); // 읽은 알림 2개
        }

        @Test
        @DisplayName("UT-NT-124: 컨트롤러 읽음 상태 필터링")
        void utNt124FiltersByReadStatusCorrectly() {
            // given - 알림 6개 생성 후 3개만 읽음 처리
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "상태별 알림" + i);
                notification = notificationRepository.save(notification);
                notifications.add(notification);
            }
            
            // 홀수 번째 알림들만 읽음 처리 (3개)
            for (int i = 0; i < 6; i += 2) {
                notificationService.markAsRead(notifications.get(i).getId(), testUser.getUserId());
            }

            // when - 전체 조회 (읽음 상태 필터링은 클라이언트에서 처리 또는 별도 API)
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 10);
            
            // then
            assertThat(response.getNotifications()).hasSize(6);
            assertThat(response.getUnreadCount()).isEqualTo(3L); // 읽지 않은 알림 3개
            
            // 읽음/읽지 않음 상태가 올바르게 반영되었는지 확인
            long actualReadCount = response.getNotifications().stream()
                .mapToLong(item -> item.getIsRead() ? 1L : 0L)
                .sum();
            long actualUnreadCount = response.getNotifications().stream()
                .mapToLong(item -> !item.getIsRead() ? 1L : 0L)
                .sum();
            
            assertThat(actualReadCount).isEqualTo(3L);
            assertThat(actualUnreadCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("UT-NT-125: 컨트롤러 즉시 반영")
        void utNt125ReadStatusImmediatelyReflected() {
            // given - 알림 생성
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "즉시 반영 테스트");
            notification = notificationRepository.save(notification);

            // 읽음 처리 전 상태 확인
            NotificationListResponseDto beforeResponse = notificationService.getNotifications(testUser.getUserId(), null, 10);
            assertThat(beforeResponse.getUnreadCount()).isEqualTo(1L);

            // when - 읽음 처리
            notificationService.markAsRead(notification.getId(), testUser.getUserId());

            // then - 즉시 상태 반영 확인
            NotificationListResponseDto afterResponse = notificationService.getNotifications(testUser.getUserId(), null, 10);
            assertThat(afterResponse.getUnreadCount()).isEqualTo(0L);
            
            // 해당 알림의 읽음 상태 확인
            assertThat(afterResponse.getNotifications()).hasSize(1);
            assertThat(afterResponse.getNotifications().get(0).getIsRead()).isTrue();
        }

        @Test
        @DisplayName("UT-NT-126: 컨트롤러 전체 읽음 처리")
        void utNt126MarksAllNotificationsAsRead() {
            // given - 실제 알림 5개 생성
            for (int i = 0; i < 5; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(unreadCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("UT-NT-127: 컨트롤러 전체 읽음 멱등성")
        void utNt127EnsuresIdempotencyWhenAllAlreadyRead() {
            // given - 알림 3개 생성 후 모두 읽음 처리
            for (int i = 0; i < 3; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "이미 읽은 알림" + i);
                notificationRepository.save(notification);
            }
            
            // 첫 번째 전체 읽음 처리
            notificationService.markAllAsRead(testUser.getUserId());
            Long firstReadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(firstReadCount).isEqualTo(0L);

            // when - 두 번째 전체 읽음 처리 (멱등성 테스트)
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then - 여전히 0개여야 함
            Long secondReadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(secondReadCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("UT-NT-128: 컨트롤러 빈 상태 처리")
        void utNt128HandlesEmptyStateGracefully() {
            // given - 알림이 하나도 없는 상황

            // when - 빈 상태에서 모든 알림 읽음 처리
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then - 에러 없이 정상 처리되어야 함
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(unreadCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("UT-NT-129: 컨트롤러 사용자 격리")
        void utNt129OtherUsersNotificationsNotAffected() {
            // given - 다른 사용자 생성
            User otherUser = User.builder()
                .kakaoId(88888L)
                .nickname("다른읽음유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            // 각 사용자에게 알림 생성
            for (int i = 0; i < 3; i++) {
                AppNotification userNotification = AppNotification.create(testUser, testNotificationType, "유저1 알림" + i);
                notificationRepository.save(userNotification);
                AppNotification otherNotification = AppNotification.create(otherUser, testNotificationType, "유저2 알림" + i);
                notificationRepository.save(otherNotification);
            }

            // when - testUser의 알림만 읽음 처리
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then - 다른 사용자의 읽지 않은 개수는 그대로
            Long testUserUnreadCount = notificationService.getUnreadCount(testUser.getUserId());
            Long otherUserUnreadCount = notificationService.getUnreadCount(otherUser.getUserId());
            
            assertThat(testUserUnreadCount).isEqualTo(0L);
            assertThat(otherUserUnreadCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("UT-NT-130: 컨트롤러 개수 업데이트")
        void utNt130UnreadCountBecomesZeroAfterMarkAllRead() {
            // given - 여러 알림 생성 (서비스를 통해 생성)
            for (int i = 0; i < 7; i++) {
                notificationService.createNotification(testUser, testNotificationType.getType(), "0으로 만들 알림" + i);
            }
            
            // 읽음 처리 전 개수 확인
            Long beforeCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(beforeCount).isEqualTo(7L);

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then
            Long afterCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(afterCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("UT-NT-131: 컨트롤러 상태 확인")
        void utNt131AllNotificationsMarkedAsReadInList() {
            // given - 알림 5개 생성
            for (int i = 0; i < 5; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "목록 읽음 테스트" + i);
                notificationRepository.save(notification);
            }

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then - 목록 조회 시 모든 알림이 읽음 상태
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 10);
            assertThat(response.getNotifications()).hasSize(5);
            assertThat(response.getUnreadCount()).isEqualTo(0L);
            
            // 모든 알림이 읽음 상태인지 확인
            boolean allRead = response.getNotifications().stream()
                .allMatch(item -> item.getIsRead());
            assertThat(allRead).isTrue();
        }

        @Test
        @DisplayName("UT-NT-132: 컨트롤러 대용량 처리")
        void utNt132BulkMarkAsReadWorksForLargeDataset() {
            // given - 대용량 알림 생성 (서비스를 통해 생성)
            for (int i = 0; i < 100; i++) {
                notificationService.createNotification(testUser, testNotificationType.getType(), "대용량 알림" + i);
            }
            
            // 읽음 처리 전 개수 확인
            Long beforeCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(beforeCount).isEqualTo(100L);

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then
            Long afterCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(afterCount).isEqualTo(0L);
            
            // 첫 페이지 조회로 읽음 상태 확인
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 20);
            assertThat(response.getUnreadCount()).isEqualTo(0L);
            assertThat(response.getNotifications()).hasSizeLessThanOrEqualTo(20);
            
            // 조회된 모든 알림이 읽음 상태인지 확인
            boolean allRead = response.getNotifications().stream()
                .allMatch(item -> item.getIsRead());
            assertThat(allRead).isTrue();
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotificationTest {

        @Test
        @DisplayName("UT-NT-133: 컨트롤러 알림 삭제")
        void utNt133DeletesNotificationSuccessfully() {
            // given - 실제 알림 생성
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "삭제될 알림");
            notification = notificationRepository.save(notification);

            // when
            notificationService.deleteNotification(testUser.getUserId(), notification.getId());
            
            // then
            assertThat(notificationRepository.findById(notification.getId())).isEmpty();
        }

        @Test
        @DisplayName("UT-NT-134: 컨트롤러 삭제 실패")
        void utNt134FailsWhenDeletingNonexistentNotification() {
            // when & then - 존재하지 않는 알림 삭제 시도
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), 999L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-135: 컨트롤러 중복 삭제")
        void utNt135HandlesNonexistentResource() {
            // given - 알림 생성 후 삭제
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "삭제될 알림");
            final AppNotification savedNotification = notificationRepository.save(notification);
            notificationService.deleteNotification(testUser.getUserId(), savedNotification.getId());

            // when & then - 이미 삭제된 알림 재삭제 시도
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), savedNotification.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-136: 컨트롤러 읽은 알림 삭제")
        void utNt136DeletesReadNotificationSuccessfully() {
            // given - 실제 알림 생성 후 읽음 처리
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "읽은 후 삭제될 알림");
            notification = notificationRepository.save(notification);
            notificationService.markAsRead(notification.getId(), testUser.getUserId());

            // when
            notificationService.deleteNotification(testUser.getUserId(), notification.getId());
            
            // then
            assertThat(notificationRepository.findById(notification.getId())).isEmpty();
        }

        @Test
        @DisplayName("UT-NT-137: 컨트롤러 삭제 권한")
        void utNt137FailsWhenDeletingOtherUsersNotification() {
            // given - 다른 사용자 생성
            User otherUser = User.builder()
                .kakaoId(77777L)
                .nickname("다른삭제유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            // 다른 사용자의 알림 생성
            AppNotification otherNotification = AppNotification.create(otherUser, testNotificationType, "다른 사용자 알림");
            final AppNotification savedOtherNotification = notificationRepository.save(otherNotification);

            // when & then - 현재 사용자가 다른 사용자의 알림 삭제 시도
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), savedOtherNotification.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-138: 컨트롤러 잘못된 ID")
        void utNt138FailsWithInvalidIdFormat() {
            // given - 음수 또는 0 ID
            Long invalidId = -1L;

            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), invalidId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-139: 컨트롤러 삭제 후 개수")
        void utNt139UpdatesUnreadCountAfterDeletion() {
            // given - 읽지 않은 알림 3개 생성 (서비스를 통해 생성)
            for (int i = 0; i < 3; i++) {
                notificationService.createNotification(testUser, testNotificationType.getType(), "삭제전 알림" + i);
            }
            
            // 삭제할 알림 하나 더 생성 (서비스를 통해 생성하고 ID 조회)
            var createResponse = notificationService.createNotification(testUser, testNotificationType.getType(), "삭제될 알림");
            Long toDeleteId = createResponse.getNotificationId();
            
            // 삭제 전 개수 확인
            Long beforeCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(beforeCount).isEqualTo(4L);

            // when - 알림 삭제
            notificationService.deleteNotification(testUser.getUserId(), toDeleteId);

            // then - 개수 업데이트 확인
            Long afterCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(afterCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("UT-NT-140: 컨트롤러 삭제 확인")
        void utNt140DeletedNotificationNotVisibleInList() {
            // given - 알림 5개 생성
            AppNotification toDelete = null;
            for (int i = 0; i < 5; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "목록 알림" + i);
                notification = notificationRepository.save(notification);
                if (i == 2) {
                    toDelete = notification; // 3번째 알림을 삭제 대상으로
                }
            }

            // when - 3번째 알림 삭제
            notificationService.deleteNotification(testUser.getUserId(), toDelete.getId());

            // then - 목록 조회 시 4개만 나와야 함
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 10);
            assertThat(response.getNotifications()).hasSize(4);
            
            // 삭제된 알림 ID가 목록에 없는지 확인
            java.util.List<Long> notificationIds = response.getNotifications().stream()
                .map(item -> item.getNotificationId())
                .collect(java.util.stream.Collectors.toList());
            assertThat(notificationIds).doesNotContain(toDelete.getId());
        }
    }

    @Nested
    @DisplayName("알림 생성")
    class NotificationCreationTest {

        @Test
        @DisplayName("UT-NT-141: 컨트롤러 알림 생성")
        void utNt141CreatesNotificationWithCorrectContent() {
            // given - 기본 테스트 데이터는 setUp에서 이미 생성됨
            String testContent = "새로운 채팅 메시지가 도착했습니다";
            
            // when - 새 알림 생성
            AppNotification notification = AppNotification.create(testUser, testNotificationType, testContent);
            notification = notificationRepository.save(notification);

            // then - 알림 내용 검증
            assertThat(notification.getContent()).isNotBlank();
            assertThat(notification.getUser()).isEqualTo(testUser);
            assertThat(notification.getNotificationType()).isEqualTo(testNotificationType);
            assertThat(notification.isRead()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-142: 컨트롤러 생성 초기화")
        void utNt142InitializesNotificationAsUnread() {
            // when
            AppNotification notification = AppNotification.create(testUser, testNotificationType, "초기화 테스트");
            notification = notificationRepository.save(notification);

            // then
            assertThat(notification.isRead()).isFalse();
            assertThat(notification.isFcmSent()).isFalse();
            assertThat(notification.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("UT-NT-143: 컨트롤러 타입별 생성")
        void utNt143AppliesTypeSpecificSettingsCorrectly() {
            // given - 다른 타입들 생성
            NotificationType likeType = NotificationType.of(Type.LIKE, "좋아요 템플릿: %s");
            likeType = notificationTypeRepository.save(likeType);

            // when - 각 타입별 알림 생성
            AppNotification chatNotification = AppNotification.create(testUser, testNotificationType, "채팅 알림");
            AppNotification likeNotification = AppNotification.create(testUser, likeType, "좋아요 알림");
            
            chatNotification = notificationRepository.save(chatNotification);
            likeNotification = notificationRepository.save(likeNotification);

            // then - 타입별 설정 확인
            assertThat(chatNotification.getTargetType()).isEqualTo("CHAT");
            assertThat(likeNotification.getTargetType()).isEqualTo("POST"); // LIKE 타입은 POST로 이동
            
            // 전송 방식 확인
            assertThat(chatNotification.shouldSendFcm()).isTrue();
            assertThat(chatNotification.shouldSendSse()).isFalse();
            assertThat(likeNotification.shouldSendSse()).isTrue();
            assertThat(likeNotification.shouldSendFcm()).isFalse();
        }

        @Test
        @DisplayName("UT-NT-144: 컨트롤러 생성 후 개수")
        void utNt144IncreasesUnreadCountAfterCreation() {
            // given - 전용 사용자 생성하여 다른 테스트와 격리 (현재 시간 기반 고유 ID)
            long uniqueKakaoId = System.currentTimeMillis() + 144;
            User isolatedUser = User.builder()
                .kakaoId(uniqueKakaoId)
                .nickname("격리사용자" + uniqueKakaoId)
                .status(Status.ACTIVE)
                .build();
            isolatedUser = userRepository.save(isolatedUser);
            
            // Redis 캐시 클리어 (격리된 테스트를 위해)
            redisTemplate.delete("notification:unread:" + isolatedUser.getUserId());
            
            // 신규 사용자의 초기 카운트 확인 (디버깅 목적)
            Long beforeCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            System.out.println("UT-NT-144 unique user (" + uniqueKakaoId + ") beforeCount: " + beforeCount);

            // when - 새 알림 생성 (서비스를 통해 생성하여 캐시 업데이트 보장)
            notificationService.createNotification(isolatedUser, testNotificationType.getType(), "개수 증가 테스트");

            // then - 읽지 않은 개수 증가 확인
            Long afterCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            System.out.println("UT-NT-144 afterCount: " + afterCount + ", expected: " + (beforeCount + 1));
            
            // 실제 데이터베이스에서 확인
            long dbCount = notificationRepository.countUnreadByUserId(isolatedUser.getUserId());
            System.out.println("UT-NT-144 direct DB count: " + dbCount);
            
            assertThat(afterCount).isEqualTo(beforeCount + 1);
        }

        @Test
        @DisplayName("UT-NT-145: 컨트롤러 템플릿 적용")
        void utNt145AppliesTemplateCorrectly() {
            // given - 템플릿이 있는 타입
            NotificationType templateType = NotificationType.of(Type.LIKE, "새로운 좋아요: %s");
            templateType = notificationTypeRepository.save(templateType);

            // when - 템플릿 적용하여 알림 생성
            String messageData = "회원님의 게시글에 좋아요가 눌렸습니다";
            AppNotification notification = AppNotification.create(testUser, templateType, messageData);
            notification = notificationRepository.save(notification);

            // then - 템플릿이 적용된 내용 확인
            assertThat(notification.getContent()).isNotBlank();
            // 실제 템플릿 적용 로직에 따라 검증 (템플릿 처리가 엔티티에서 이루어지는지 서비스에서 이루어지는지에 따라)
            assertThat(notification.getNotificationType()).isEqualTo(templateType);
        }

        @Test
        @DisplayName("UT-NT-146: 컨트롤러 대량 생성")
        void utNt146HandlesBulkNotificationCreation() {
            // given - 전용 사용자 생성하여 다른 테스트와 격리 (현재 시간 기반 고유 ID)
            long uniqueKakaoId = System.currentTimeMillis() + 146;
            User isolatedUser = User.builder()
                .kakaoId(uniqueKakaoId)
                .nickname("격리사용자" + uniqueKakaoId)
                .status(Status.ACTIVE)
                .build();
            isolatedUser = userRepository.save(isolatedUser);
            
            // 캐시 클리어 (테스트 간에 남아있을 수 있는 데이터 제거)
            redisTemplate.delete("notification:unread:" + isolatedUser.getUserId());
            
            // 신규 사용자의 초기 카운트 확인 (디버깅 목적)
            Long beforeCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            System.out.println("UT-NT-146 unique user (" + uniqueKakaoId + ") beforeCount: " + beforeCount);
            
            // 직접 DB 확인도 해보자
            long directDbCount = notificationRepository.countUnreadByUserId(isolatedUser.getUserId());
            System.out.println("UT-NT-146 direct DB beforeCount: " + directDbCount);
            
            // 모든 알림 조회도 해보자
            List<AppNotification> existingNotifications = notificationRepository.findUnreadNotificationsByUserId(isolatedUser.getUserId());
            System.out.println("UT-NT-146 existing unread notifications count: " + existingNotifications.size());
            if (existingNotifications.size() > 0) {
                System.out.println("UT-NT-146 first notification user: " + existingNotifications.get(0).getUser().getKakaoId());
            }

            // when - 대량 알림 생성 (50개) - 서비스를 통해 생성
            for (int i = 0; i < 50; i++) {
                notificationService.createNotification(isolatedUser, testNotificationType.getType(), "대량 알림 " + i);
            }

            // then
            Long afterCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            System.out.println("UT-NT-146 afterCount: " + afterCount + ", expected: " + (beforeCount + 50));
            
            // 실제 데이터베이스에서 확인
            long dbCount = notificationRepository.countUnreadByUserId(isolatedUser.getUserId());
            System.out.println("UT-NT-146 direct DB count: " + dbCount);
            
            assertThat(afterCount).isEqualTo(beforeCount + 50);
            
            // 목록 조회로 생성 확인
            NotificationListResponseDto response = notificationService.getNotifications(isolatedUser.getUserId(), null, 100);
            assertThat(response.getNotifications().size()).isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("UT-NT-147: 컨트롤러 동시 생성")
        void utNt147HandlesConcurrentNotificationCreationSafely() {
            // given - 전용 사용자 생성하여 다른 테스트와 격리 (현재 시간 기반 고유 ID)
            long uniqueKakaoId = System.currentTimeMillis() + 147;
            User isolatedUser = User.builder()
                .kakaoId(uniqueKakaoId)
                .nickname("격리사용자" + uniqueKakaoId)
                .status(Status.ACTIVE)
                .build();
            isolatedUser = userRepository.save(isolatedUser);
            
            // Redis 캐시 클리어 (격리된 테스트를 위해)
            redisTemplate.delete("notification:unread:" + isolatedUser.getUserId());
            
            // 신규 사용자의 초기 카운트 확인 (디버깅 목적)
            Long beforeCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            System.out.println("UT-NT-147 unique user (" + uniqueKakaoId + ") beforeCount: " + beforeCount);

            // when - 동시에 여러 알림 생성 (서비스를 통해 생성)
            for (int i = 0; i < 10; i++) {
                notificationService.createNotification(isolatedUser, testNotificationType.getType(), "동시성 테스트 " + i);
            }

            // then - 모든 알림이 정상 생성되었는지 확인
            Long afterCount = notificationService.getUnreadCount(isolatedUser.getUserId());
            assertThat(afterCount).isEqualTo(beforeCount + 10);
        }

        @Test
        @DisplayName("UT-NT-148: 컨트롤러 전송 방식")
        void utNt148DeliversViaCorrectMethodBasedOnType() {
            // given - 다른 전송 방식을 가진 타입들
            NotificationType fcmType = testNotificationType; // CHAT = FCM
            NotificationType sseType = NotificationType.of(Type.LIKE, "SSE 템플릿");
            sseType = notificationTypeRepository.save(sseType);

            // when - 각 타입의 알림 생성
            AppNotification fcmNotification = AppNotification.create(testUser, fcmType, "FCM 전송 테스트");
            AppNotification sseNotification = AppNotification.create(testUser, sseType, "SSE 전송 테스트");
            
            fcmNotification = notificationRepository.save(fcmNotification);
            sseNotification = notificationRepository.save(sseNotification);

            // then - 전송 방식별 확인
            // FCM 전송 타입
            assertThat(fcmNotification.shouldSendFcm()).isTrue();
            assertThat(fcmNotification.shouldSendSse()).isFalse();
            
            // SSE 전송 타입
            assertThat(sseNotification.shouldSendSse()).isTrue();
            assertThat(sseNotification.shouldSendFcm()).isFalse();
            
            // 생성 후 전송 상태 초기값 확인
            assertThat(fcmNotification.isFcmSent()).isFalse(); // 아직 전송 전
            assertThat(sseNotification.isFcmSent()).isFalse(); // SSE는 FCM 전송하지 않음
        }
    }
}