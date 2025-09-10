package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 생성 서비스 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@DisplayName("알림 생성 서비스 테스트")
class NotificationCreationServiceTest {

    @Autowired
    private NotificationCreationService notificationCreationService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private ApplicationEvents events;

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .kakaoId(12345L)
                .nickname("테스트사용자")
                .status(Status.ACTIVE)
                .build();
        userRepository.save(testUser);

        testNotificationType = NotificationType.of(Type.COMMENT, "멘션 알림입니다");
        notificationTypeRepository.save(testNotificationType);
    }

    @Test
    @DisplayName("알림 생성 성공")
    void createNotification_Success() {
        // when
        Notification notification = notificationCreationService.createNotification(
                testUser, Type.COMMENT, "테스트 메시지"
        );

        // then
        assertThat(notification).isNotNull();
        assertThat(notification.getUser()).isEqualTo(testUser);
        assertThat(notification.getNotificationType().getType()).isEqualTo(Type.COMMENT);
        assertThat(notification.isRead()).isFalse();

        // 이벤트 발행 확인
        long eventCount = events.stream(NotificationCreatedEvent.class).count();
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("대량 알림 생성 성공")
    void createBulkNotifications_Success() {
        // given
        User user2 = User.builder()
                .kakaoId(12346L)
                .nickname("테스트사용자2")
                .status(Status.ACTIVE)
                .build();
        userRepository.save(user2);

        List<User> users = List.of(testUser, user2);

        // when
        notificationCreationService.createBulkNotifications(users, Type.COMMENT, "대량 알림");

        // then
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2);
        
        // 이벤트 발행 확인
        long eventCount = events.stream(NotificationCreatedEvent.class).count();
        assertThat(eventCount).isEqualTo(2);
    }

    @Test
    @DisplayName("존재하지 않는 알림 타입으로 생성 실패")
    void createNotification_WithInvalidType_ThrowsException() {
        // given
        notificationTypeRepository.deleteAll();

        // when & then
        assertThatThrownBy(() -> notificationCreationService.createNotification(
                testUser, Type.COMMENT, "테스트 메시지"
        )).isInstanceOf(CustomException.class);
    }
}