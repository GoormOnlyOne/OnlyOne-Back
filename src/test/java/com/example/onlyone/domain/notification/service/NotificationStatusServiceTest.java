package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 상태 관리 서비스 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 상태 관리 서비스 테스트")
class NotificationStatusServiceTest {

    @Autowired
    private NotificationStatusService notificationStatusService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;

    private User testUser;
    private NotificationType testNotificationType;
    private Notification testNotification;

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

        testNotification = Notification.create(testUser, testNotificationType, "테스트 알림");
        notificationRepository.save(testNotification);
    }

    @Test
    @DisplayName("알림 읽음 처리 성공")
    void markAsRead_Success() {
        // given
        assertThat(testNotification.isRead()).isFalse();

        // when
        notificationStatusService.markAsRead(testNotification.getId(), testUser.getUserId());

        // then
        Notification updated = notificationRepository.findById(testNotification.getId()).get();
        assertThat(updated.isRead()).isTrue();
    }

    @Test
    @DisplayName("모든 알림 읽음 처리 성공")
    void markAllAsRead_Success() {
        // given
        Notification notification2 = Notification.create(testUser, testNotificationType, "알림2");
        notificationRepository.save(notification2);

        assertThat(testNotification.isRead()).isFalse();
        assertThat(notification2.isRead()).isFalse();

        // when
        notificationStatusService.markAllAsRead(testUser.getUserId());

        // then
        long unreadCount = notificationRepository.countUnreadByUserId(testUser.getUserId());
        assertThat(unreadCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("알림 삭제 성공")
    void deleteNotification_Success() {
        // when
        notificationStatusService.deleteNotification(testUser.getUserId(), testNotification.getId());

        // then
        Optional<Notification> deleted = notificationRepository.findById(testNotification.getId());
        assertThat(deleted).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 알림 읽음 처리 실패")
    void markAsRead_WithUnauthorizedUser_ThrowsException() {
        // given
        User otherUser = User.builder()
                .email("other@example.com")
                .name("다른사용자")
                .status(Status.ACTIVE)
                .build();
        userRepository.save(otherUser);

        // when & then
        assertThatThrownBy(() -> notificationStatusService.markAsRead(
                testNotification.getId(), otherUser.getUserId()
        )).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("존재하지 않는 알림 삭제 실패")
    void deleteNotification_WithInvalidId_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> notificationStatusService.deleteNotification(
                testUser.getUserId(), 99999L
        )).isInstanceOf(CustomException.class);
    }
}