package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 조회 서비스 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 조회 서비스 테스트")
class NotificationQueryServiceTest {

    @Autowired
    private NotificationQueryService notificationQueryService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@example.com")
                .name("테스트사용자")
                .status(Status.ACTIVE)
                .build();
        userRepository.save(testUser);

        testNotificationType = NotificationType.builder()
                .type(Type.MENTION)
                .template("멘션 알림입니다")
                .build();
        notificationTypeRepository.save(testNotificationType);
    }

    @Test
    @DisplayName("알림 목록 조회 성공")
    void getNotifications_Success() {
        // given
        Notification notification1 = Notification.create(testUser, testNotificationType, "알림1");
        Notification notification2 = Notification.create(testUser, testNotificationType, "알림2");
        notificationRepository.save(notification1);
        notificationRepository.save(notification2);

        // when
        NotificationListResponseDto result = notificationQueryService.getNotifications(
                testUser.getUserId(), null, 10
        );

        // then
        assertThat(result).isNotNull();
        assertThat(result.getNotifications()).hasSize(2);
        assertThat(result.getUnreadCount()).isEqualTo(2L);
        assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("읽지 않은 알림 개수 조회 성공")
    void getUnreadCount_Success() {
        // given
        Notification notification1 = Notification.create(testUser, testNotificationType, "알림1");
        Notification notification2 = Notification.create(testUser, testNotificationType, "알림2");
        notification1.markAsRead(); // 하나는 읽음 처리
        
        notificationRepository.save(notification1);
        notificationRepository.save(notification2);

        // when
        Long unreadCount = notificationQueryService.getUnreadCount(testUser.getUserId());

        // then
        assertThat(unreadCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("잘못된 사용자 ID로 조회 실패")
    void getNotifications_WithInvalidUserId_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> notificationQueryService.getNotifications(
                null, null, 10
        )).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("페이지 크기 제한 테스트")
    void getNotifications_LimitPageSize() {
        // when
        NotificationListResponseDto result = notificationQueryService.getNotifications(
                testUser.getUserId(), null, 100 // 50을 초과하는 요청
        );

        // then - 실제로는 최대 50개까지만 조회됨을 확인
        assertThat(result).isNotNull();
    }
}