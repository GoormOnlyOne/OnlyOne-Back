package com.example.onlyone.domain.notification.performance;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * 알림 생성 성능 테스트
 * - 1000개 알림 생성 기준
 * - Micrometer 메트릭 수집
 */
@Disabled
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@Rollback
@DisplayName("알림 생성 성능 테스트")
public class NotificationWritePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 테스트용 사용자 생성
        testUser = User.builder()
                .kakaoId(System.currentTimeMillis())
                .nickname("성능테스트유저_" + System.currentTimeMillis())
                .status(Status.ACTIVE)
                .fcmToken("test_fcm_token_performance")
                .build();
        testUser = userRepository.save(testUser);
        
        // 테스트용 알림 타입 생성
        testNotificationType = notificationTypeRepository
                .findByType(Type.CHAT)
                .orElseGet(() -> {
                    NotificationType newType = NotificationType.of(Type.CHAT, "성능테스트 템플릿: %s");
                    return notificationTypeRepository.save(newType);
                });
        
        System.gc();
    }
    
    @AfterEach
    void tearDown() {
        // @Transactional과 @Rollback으로 자동 정리
    }

    @Test
    @DisplayName("1000개 알림 생성 성능 테스트")
    @Timeout(90)
    void create1KNotificationsPerformanceTest() {
        // given
        int notificationCount = 1_000;
        
        // when
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant startTime = Instant.now();
        
        for (int i = 0; i < notificationCount; i++) {
            NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
                    .userId(testUser.getUserId())
                    .type(Type.CHAT)
                    .args(new String[]{"성능테스트 메시지 " + i})
                    .build();
            
            notificationService.createNotification(requestDto);
        }
        
        Instant endTime = Instant.now();
        sample.stop(Timer.builder("notification.creation.performance")
                .description("알림 생성 성능 테스트")
                .tag("count", String.valueOf(notificationCount))
                .register(meterRegistry));
        
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력 및 검증
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 1000개 알림 생성 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.2f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 성능 기준: 최소 10 TPS 이상, 90초 미만
        assertThat(tps).isGreaterThan(10.0);
        assertThat(seconds).isLessThan(90.0);
        
        // 메트릭 기록
        meterRegistry.counter("test.notification.created.total", "result", "success")
                .increment(notificationCount);
        meterRegistry.gauge("test.notification.tps", tps);
    }
}