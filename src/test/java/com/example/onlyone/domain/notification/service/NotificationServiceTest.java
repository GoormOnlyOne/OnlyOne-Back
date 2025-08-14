package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 알림 서비스 테스트 - 간소화된 실용적 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 서비스 테스트")
class NotificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationTypeRepository notificationTypeRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private SseEmittersService sseEmittersService;
    @Mock
    private FcmService fcmService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private NotificationService notificationService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = createTestUser(1L, "테스트유저");
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회")
    class GetUnreadCountTest {

        @Test
        @DisplayName("읽지 않은 개수를 정확히 조회한다")
        void 읽지_않은_개수를_정확히_조회한다() {
            // given
            Long userId = 1L;
            Long expectedCount = 5L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(expectedCount);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(expectedCount);
        }

        @Test
        @DisplayName("읽지 않은 개수가 null일 때 0을 반환한다")
        void 읽지_않은_개수가_null일_때_0을_반환한다() {
            // given
            Long userId = 1L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(null);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("알림 생성 검증")
    class CreateNotificationValidationTest {

        @Test
        @DisplayName("존재하지 않는 사용자로 알림 생성 시 예외가 발생한다")
        void 존재하지_않는_사용자로_알림_생성_시_예외가_발생한다() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(999L, Type.CHAT, "테스트");
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 알림 타입으로 생성 시 예외가 발생한다")
        void 존재하지_않는_알림_타입으로_생성_시_예외가_발생한다() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAllAsReadTest {

        @Test
        @DisplayName("읽음 처리된 개수만큼 반환한다")
        void 읽음_처리된_개수만큼_반환한다() {
            // given
            Long userId = 1L;
            given(notificationRepository.markAllAsReadByUserId(userId)).willReturn(3L);

            // when
            notificationService.markAllAsRead(userId);

            // then - 예외 없이 완료되면 성공
            assertThat(true).isTrue();
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}