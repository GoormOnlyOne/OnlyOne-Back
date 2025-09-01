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
import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.global.sse.SseEmittersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 알림 이벤트 핸들러 및 SSE 전송 기능 테스트
 * - @TransactionalEventListener 메서드 호출 검증
 * - SSE 전송 로직 검증
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 이벤트 처리 및 SSE 전송 테스트")
class NotificationEventHandlerTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    
    @MockitoSpyBean  // 실제 서비스를 스파이로 만들어서 메서드 호출 검증
    private NotificationService notificationService;
    
    @MockitoBean  // SSE 서비스를 목으로 만들어서 호출 검증
    private SseEmittersService sseEmittersService;
    

    private User testUser;
    private NotificationType chatType;
    private NotificationType likeType;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
        // 테스트 데이터 생성
        testUser = User.builder()
            .kakaoId(12345L)
            .nickname("이벤트테스트유저")
            .status(Status.ACTIVE)
            .fcmToken("test_fcm_token_123")
            .build();
        testUser = userRepository.save(testUser);
        
        chatType = NotificationType.of(Type.CHAT, "채팅: %s");
        chatType = notificationTypeRepository.save(chatType);
        
        likeType = NotificationType.of(Type.LIKE, "좋아요: %s");  
        likeType = notificationTypeRepository.save(likeType);
        
    }

    @Nested
    @DisplayName("알림 이벤트 처리")
    class HandleNotificationEvent {
        
        @Test
        @DisplayName("SSE 알림 이벤트 핸들러 호출 성공")
        void sseNotificationEventHandler_success() throws Exception {
            // given
            Notification sseNotification = Notification.create(testUser, chatType, "SSE 테스트");
            sseNotification = notificationRepository.save(sseNotification);
            
            // when
            NotificationCreatedEvent event = new NotificationCreatedEvent(sseNotification);
            notificationService.handleNotificationCreated(event);

            // then
            verify(sseEmittersService, timeout(2000)).sendEvent(eq(testUser.getUserId()), eq("notification"), eq(sseNotification));
        }
        
        @Test
        @DisplayName("모든 알림 SSE 전송 검증 성공")
        void allNotificationsSentViaSse_success() throws Exception {
            // given  
            Notification likeNotification = Notification.create(testUser, likeType, "좋아요 테스트");
            likeNotification = notificationRepository.save(likeNotification);
            
            // when
            NotificationCreatedEvent event = new NotificationCreatedEvent(likeNotification);
            notificationService.handleNotificationCreated(event);

            // then
            verify(sseEmittersService, timeout(2000)).sendEvent(eq(testUser.getUserId()), eq("notification"), eq(likeNotification));
        }
        
        @Test
        @DisplayName("SSE 연결 없을 때 처리 성공")
        void handlesMissingSseConnection_success() throws Exception {
            // given
            when(sseEmittersService.isUserConnected(testUser.getUserId())).thenReturn(false);
            
            Notification sseNotification = Notification.create(testUser, chatType, "연결 없음 테스트");
            sseNotification = notificationRepository.save(sseNotification);
            
            // when
            NotificationCreatedEvent event = new NotificationCreatedEvent(sseNotification);
            notificationService.handleNotificationCreated(event);

            // then
            verify(sseEmittersService, timeout(2000)).sendEvent(eq(testUser.getUserId()), eq("notification"), eq(sseNotification));
        }
        
        @Test
        @DisplayName("이벤트 발행 검증 성공")
        void eventPublishing_success() {
            // given
            when(sseEmittersService.isUserConnected(testUser.getUserId())).thenReturn(true);
            
            Notification chatNotification = Notification.create(testUser, chatType, "이벤트 발행 테스트");
            chatNotification = notificationRepository.save(chatNotification);

            // when
            NotificationCreatedEvent event = new NotificationCreatedEvent(chatNotification);
            notificationService.handleNotificationCreated(event);

            // then
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getUserId()), eq("notification"), eq(chatNotification));
        }
    }
}