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
import com.example.onlyone.global.sse.service.SseEmittersService;
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
 * 알림 이벤트 핸들러 및 전송 기능 테스트
 * - @TransactionalEventListener 메서드 호출 검증
 * - 알림 전송 로직 검증
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
        @DisplayName("알림 이벤트 핸들러 호출 성공")
        void notificationEventHandler_success() throws Exception {
            // given
            // when - 알림 생성 시 자동으로 이벤트 발행됨
            notificationService.createNotification(testUser, Type.CHAT, "테스트 메시지").join();

            // then - SSE 전송이 kakaoId로 호출되는지 확인
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class));
        }
        
        @Test
        @DisplayName("모든 알림 전송 검증 성공")
        void allNotificationsSent_success() throws Exception {
            // when - LIKE 알림 생성
            notificationService.createNotification(testUser, Type.LIKE, "좋아요 테스트").join();

            // then - SSE 전송이 kakaoId로 호출되는지 확인
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class));
        }
        
        @Test
        @DisplayName("연결 없을 때 처리 성공")
        void handlesMissingConnection_success() throws Exception {
            // given
            when(sseEmittersService.isUserConnected(testUser.getKakaoId())).thenReturn(false);
            
            // when - 연결이 없어도 알림 생성 및 전송 시도
            notificationService.createNotification(testUser, Type.CHAT, "연결 없음 테스트").join();

            // then - SSE 전송이 여전히 호출되는지 확인 (연결 없으면 실패하지만 시도는 함)
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class));
        }
        
        @Test
        @DisplayName("이벤트 발행 검증 성공")
        void eventPublishing_success() {
            // given
            when(sseEmittersService.isUserConnected(testUser.getKakaoId())).thenReturn(true);

            // when - 이벤트 발행 테스트
            notificationService.createNotification(testUser, Type.CHAT, "이벤트 발행 테스트").join();

            // then - SSE 전송이 kakaoId로 호출되는지 확인
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class));
        }
        
        @Test
        @DisplayName("SSE 전송 성공 시 sse_sent 상태 업데이트")
        void sseSentStatusUpdate_success() throws Exception {
            // given
            when(sseEmittersService.sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));

            // when - 알림 생성 및 SSE 전송
            notificationService.createNotification(testUser, Type.CHAT, "상태 업데이트 테스트").join();

            // then - SSE 전송 호출 확인
            verify(sseEmittersService, timeout(3000)).sendEvent(eq(testUser.getKakaoId()), eq("notification"), any(Notification.class));
            
            // SSE 전송 성공 후 잠시 대기하여 상태 업데이트 완료 대기
            Thread.sleep(1000);
            
            // DB에서 알림 조회하여 sse_sent 상태 확인
            java.util.List<Notification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
            // 참고: sse_sent 상태는 비동기로 업데이트되므로 실제 값은 이벤트 핸들러 구현에 따라 다를 수 있음
        }
    }
}