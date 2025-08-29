package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * 알림 이벤트 핸들러 및 SSE/FCM 전송 기능 테스트
 * - @TransactionalEventListener 메서드 호출 검증
 * - SSE 및 FCM 전송 로직 검증
 * - Redis 헬스 체크 및 폴백 로직 검증
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("알림 이벤트 처리 및 전송 테스트")
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
    
    @MockitoBean  // FCM 서비스를 목으로 만들어서 호출 검증
    private FcmService fcmService;
    
    @MockitoBean  // Redis 헬스 체커를 목으로 만들어서 상태 제어
    private RedisHealthChecker redisHealthChecker;

    private User testUser;
    private NotificationType chatType;  // FCM 전송 타입
    private NotificationType likeType;  // SSE 전송 타입

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
        
        // FCM 전송 타입 (CHAT)
        chatType = NotificationType.of(Type.CHAT, "채팅: %s");
        chatType = notificationTypeRepository.save(chatType);
        
        // SSE 전송 타입 (LIKE)
        likeType = NotificationType.of(Type.LIKE, "좋아요: %s");  
        likeType = notificationTypeRepository.save(likeType);
        
        // Mock 기본 동작 설정
        when(redisHealthChecker.isHealthy()).thenReturn(true);  // 기본적으로 Redis 정상
    }

    @Test
    @DisplayName("UT-NT-068: FCM 전송 타입 이벤트 핸들러 호출")
    void utNt068FcmTypeTriggersEventHandler() throws Exception {
        // given
        AppNotification fcmNotification = AppNotification.create(testUser, chatType, "FCM 테스트");
        fcmNotification = notificationRepository.save(fcmNotification);
        
        // when - 이벤트 핸들러 직접 호출
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(fcmNotification);
        notificationService.handleNotificationCreated(event);

        // then - FCM 전송이 호출되어야 함 (비동기 호출이므로 timeout 사용)
        verify(fcmService, timeout(2000)).sendFcmNotification(eq(fcmNotification));
        verify(sseEmittersService, never()).sendSseNotification(any(), any());
    }

    @Test
    @DisplayName("UT-NT-069: SSE 전송 타입 이벤트 핸들러 호출")
    void utNt069SseTypeTriggersEventHandler() throws Exception {
        // given  
        AppNotification sseNotification = AppNotification.create(testUser, likeType, "SSE 테스트");
        sseNotification = notificationRepository.save(sseNotification);
        
        // when - 이벤트 핸들러 직접 호출
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(sseNotification);
        notificationService.handleNotificationCreated(event);

        // then - SSE 전송이 호출되어야 함
        verify(sseEmittersService, timeout(2000)).sendSseNotification(eq(testUser.getUserId()), eq(sseNotification));
        verify(fcmService, never()).sendFcmNotification(any());
    }

    @Test
    @DisplayName("UT-NT-070: Redis 비정상 시 FCM 폴백")
    void utNt070RedisUnhealthyTriggersFcmFallback() throws Exception {
        // given - Redis 비정상 상태로 설정
        when(redisHealthChecker.isHealthy()).thenReturn(false);
        
        AppNotification sseNotification = AppNotification.create(testUser, likeType, "폴백 테스트");
        sseNotification = notificationRepository.save(sseNotification);
        
        // when - SSE 전송 타입이지만 Redis가 비정상
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(sseNotification);
        notificationService.handleNotificationCreated(event);

        // then - SSE는 호출되지 않고 FCM 폴백이 호출되어야 함
        verify(sseEmittersService, never()).sendSseNotification(any(), any());
        verify(fcmService, timeout(2000)).sendFcmNotification(eq(sseNotification));
    }

    @Test
    @DisplayName("UT-NT-071: SSE 실패 시 FCM 폴백")
    void utNt071SseFailureTriggersFcmFallback() throws Exception {
        // given - SSE 전송 실패하도록 설정
        doThrow(new RuntimeException("SSE connection failed"))
            .when(sseEmittersService).sendSseNotification(any(), any());
        
        AppNotification sseNotification = AppNotification.create(testUser, likeType, "SSE 실패 테스트");
        sseNotification = notificationRepository.save(sseNotification);
        
        // when
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(sseNotification);
        notificationService.handleNotificationCreated(event);

        // then - SSE 시도 후 실패하면 FCM 폴백 호출
        verify(sseEmittersService, timeout(2000)).sendSseNotification(eq(testUser.getUserId()), eq(sseNotification));
        verify(fcmService, timeout(2000)).sendFcmNotification(eq(sseNotification));
    }

    @Test
    @DisplayName("UT-NT-072: FCM 토큰 없을 때 처리")
    void utNt072HandlesMissingFcmToken() throws Exception {
        // given - FCM 토큰이 없는 사용자
        testUser.clearFcmToken();  // FCM 토큰 제거
        testUser = userRepository.save(testUser);
        
        AppNotification fcmNotification = AppNotification.create(testUser, chatType, "토큰 없음 테스트");
        fcmNotification = notificationRepository.save(fcmNotification);
        
        // when
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(fcmNotification);
        notificationService.handleNotificationCreated(event);

        // then - FCM 토큰이 없으므로 FCM 서비스는 호출되지 않아야 함
        Thread.sleep(1000); // 비동기 처리 완료 대기
        verify(fcmService, never()).sendFcmNotification(any());
    }

    @Test
    @DisplayName("UT-NT-073: 전송 방식 결정 로직 검증")
    void utNt073DeliveryMethodLogicWorks() {
        // given
        DeliveryMethod chatDelivery = chatType.getDeliveryMethod();
        DeliveryMethod likeDelivery = likeType.getDeliveryMethod();
        
        // then - 전송 방식이 올바르게 설정되었는지 검증
        assertThat(chatDelivery.shouldSendFcm()).isTrue();
        assertThat(chatDelivery.shouldSendSse()).isFalse();
        
        assertThat(likeDelivery.shouldSendSse()).isTrue(); 
        assertThat(likeDelivery.shouldSendFcm()).isFalse();
    }

    @Test
    @DisplayName("UT-NT-074: 이벤트 발행 자체 검증")
    void utNt074EventPublishingWorks() {
        // given - FCM 토큰이 있는 사용자로 설정
        testUser.updateFcmToken("test_fcm_token_for_event_test");
        testUser = userRepository.save(testUser);
        
        AppNotification chatNotification = AppNotification.create(testUser, chatType, "이벤트 발행 테스트");
        chatNotification = notificationRepository.save(chatNotification);

        // when - 이벤트 핸들러 직접 호출 (트랜잭션 분리를 위해)
        NotificationCreatedEvent event = 
            new NotificationCreatedEvent(chatNotification);
        notificationService.handleNotificationCreated(event);

        // then - FCM 전송이 호출되었는지 검증
        verify(fcmService, timeout(3000)).sendFcmNotification(eq(chatNotification));
    }
}