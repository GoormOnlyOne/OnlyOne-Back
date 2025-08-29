package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.request.BatchNotificationRequestDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 알림 생성 전용 테스트 클래스
 * - 다른 테스트와의 간섭을 피하기 위해 독립적으로 분리
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("배치 알림 서비스 테스트")
class NotificationBatchServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationTypeRepository notificationTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 배치 테스트용 사용자 생성
        testUser = createTestUser(10000L, "배치테스트유저");

        // 배치 테스트용 NotificationType 생성
        testNotificationType = NotificationType.of(Type.CHAT, "배치 테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
    }

    @Test
    @DisplayName("NS-001: 배치 알림 생성 기능")
    void ns001CreatesBatchNotificationsSuccessfully() {
        // given - 배치 생성용 데이터 준비
        List<BatchNotificationRequestDto> batchRequests = new ArrayList<>();

        // 여러 사용자에게 같은 타입의 알림 생성
        User user2 = createTestUser(20000L, "batchUser2");
        User user3 = createTestUser(30000L, "batchUser3");

        batchRequests.add(BatchNotificationRequestDto.of(testUser.getUserId(), Type.CHAT, "배치채팅"));
        batchRequests.add(BatchNotificationRequestDto.of(user2.getUserId(), Type.CHAT, "배치채팅2"));
        batchRequests.add(BatchNotificationRequestDto.of(user3.getUserId(), Type.CHAT, "배치채팅3"));

        // when
        int createdCount = notificationService.createBatchNotifications(batchRequests);

        // then
        assertThat(createdCount).isEqualTo(3);

        // 실제 저장된 알림 검증 (배치로 생성된 3개만)
        List<AppNotification> savedNotifications = notificationRepository.findAll();
        assertThat(savedNotifications).hasSize(3); // 배치 3개

        // 배치로 생성된 알림 확인
        List<AppNotification> batchNotifications = savedNotifications.stream()
            .filter(n -> n.getContent().contains("배치채팅"))
            .collect(Collectors.toList());
        assertThat(batchNotifications).hasSize(3);
        assertThat(batchNotifications)
            .extracting(AppNotification::getContent)
            .containsExactlyInAnyOrder("배치 테스트 템플릿: 배치채팅", "배치 테스트 템플릿: 배치채팅2", "배치 테스트 템플릿: 배치채팅3");
    }

    @Test
    @DisplayName("NS-002: 빈 배치 요청 처리")
    void ns002HandlesEmptyBatchRequest() {
        // given
        List<BatchNotificationRequestDto> emptyBatchRequests = new ArrayList<>();

        // when
        int createdCount = notificationService.createBatchNotifications(emptyBatchRequests);

        // then
        assertThat(createdCount).isEqualTo(0);
        // 알림이 생성되지 않음
        assertThat(notificationRepository.findAll()).hasSize(0);
    }

    @Test
    @DisplayName("NS-003: 대량 배치 알림 생성")
    void ns003CreatesManyBatchNotifications() {
        // given - 100개의 배치 요청 생성
        List<BatchNotificationRequestDto> largeBatchRequests = new ArrayList<>();
        List<User> users = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            User user = createTestUser(30000L + i, "batchUser" + i);
            users.add(user);
            largeBatchRequests.add(BatchNotificationRequestDto.of(user.getUserId(), Type.CHAT, "대량배치" + i));
        }

        // when
        int createdCount = notificationService.createBatchNotifications(largeBatchRequests);

        // then
        assertThat(createdCount).isEqualTo(100);
        assertThat(notificationRepository.findAll()).hasSize(100); // 배치 100개

        // 대량 배치로 생성된 알림만 확인
        List<AppNotification> batchNotifications = notificationRepository.findAll().stream()
            .filter(n -> n.getContent().contains("대량배치"))
            .collect(Collectors.toList());
        assertThat(batchNotifications).hasSize(100);
    }

    private User createTestUser(Long uniqueId, String nickname) {
        User user = User.builder()
                .kakaoId(uniqueId)
                .nickname(nickname)
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울시")
                .district("강남구")
                .build();
        return userRepository.save(user);
    }
}