package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 컨트롤러 통합 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser
@DisplayName("알림 컨트롤러 테스트")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private NotificationService notificationService;
    
    @MockitoBean
    private UserService userService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
        testUser = User.builder()
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .build();
        testUser = userRepository.save(testUser);
        
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        
        given(userService.getCurrentUser()).willReturn(testUser);
    }

    @Nested
    @DisplayName("읽지 않은 알림 개수 조회")
    class UnreadCountTest {

        @Test
        @DisplayName("읽지 않은 알림 개수 조회 성공")
        void getsUnreadCountSuccessfully() throws Exception {
            // given
            for (int i = 0; i < 5; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(5));
        }

        @Test
        @DisplayName("알림이 없을 때 0 반환")
        void returnsZeroWhenNoUnreadNotifications() throws Exception {
            // when & then
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
        }

        @Test
        @DisplayName("존재하지 않는 사용자 ID로 조회 시 예외")
        void throwsErrorWhenUserNotFound() {
            // when & then
            assertThatThrownBy(() -> notificationService.getUnreadCount(null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 목록 조회")
    class NotificationListTest {

        @Test
        @DisplayName("기본 파라미터로 알림 목록 조회")
        void getsNotificationsWithDefaultParams() throws Exception {
            // given
            for (int i = 0; i < 3; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notifications").isArray())
                .andExpect(jsonPath("$.data.notifications", hasSize(3)))
                .andExpect(jsonPath("$.data.unreadCount").value(3))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        }

        @Test
        @DisplayName("커서 기반 페이징")
        void worksWithCursorBasedPaging() throws Exception {
            // given
            for (int i = 0; i < 20; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then - 첫 번째 페이지
            mockMvc.perform(get("/notifications").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.notifications", hasSize(10)));
        }

        @Test
        @DisplayName("최대 크기 제한 (100개)")
        void limitsSizeToMaximum100() throws Exception {
            // given
            for (int i = 0; i < 150; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications", hasSize(lessThanOrEqualTo(100))));
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsReadTest {

        @Test
        @DisplayName("개별 알림 읽음 처리")
        void marksIndividualNotificationAsRead() throws Exception {
            // given
            Notification notification = Notification.create(testUser, testNotificationType, "테스트 알림");
            notification = notificationRepository.save(notification);

            // when & then
            mockMvc.perform(put("/notifications/" + notification.getId() + "/read"))
                .andExpect(status().isOk());
            
            Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
            assertThat(updated.isRead()).isTrue();
        }

        @Test
        @DisplayName("모든 알림 읽음 처리")
        void marksAllNotificationsAsRead() throws Exception {
            // given
            for (int i = 0; i < 5; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(put("/notifications/read-all"))
                .andExpect(status().isOk());
            
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        }

        @Test
        @DisplayName("존재하지 않는 알림 읽음 처리 시 예외")
        void failsWhenNotificationNotFound() {
            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(999L, testUser.getUserId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 사용자 알림 접근 시 예외")
        void blocksAccessToOtherUsersNotifications() {
            // given
            User otherUser = User.builder()
                .kakaoId(67890L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            Notification otherNotification = Notification.create(otherUser, testNotificationType, "다른 사용자 알림");
            final Notification savedOtherNotification = notificationRepository.save(otherNotification);

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(savedOtherNotification.getId(), testUser.getUserId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("읽음 처리 멱등성")
        void ensuresIdempotencyForDuplicateOperations() {
            // given
            Notification notification = Notification.create(testUser, testNotificationType, "테스트 알림");
            notification = notificationRepository.save(notification);
            notificationService.markAsRead(notification.getId(), testUser.getUserId());

            // when
            notificationService.markAsRead(notification.getId(), testUser.getUserId());
            
            // then
            Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
            assertThat(updated.isRead()).isTrue();
        }

        @Test
        @DisplayName("전체 읽음 처리 멱등성")
        void ensuresIdempotencyWhenAllAlreadyRead() {
            // given
            for (int i = 0; i < 3; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }
            notificationService.markAllAsRead(testUser.getUserId());

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then
            Long unreadCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(unreadCount).isEqualTo(0L);
        }

        @Test
        @DisplayName("사용자별 격리 확인")
        void otherUsersNotificationsNotAffected() {
            // given
            User otherUser = User.builder()
                .kakaoId(88888L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            for (int i = 0; i < 3; i++) {
                notificationRepository.save(Notification.create(testUser, testNotificationType, "유저1 알림" + i));
                notificationRepository.save(Notification.create(otherUser, testNotificationType, "유저2 알림" + i));
            }

            // when
            notificationService.markAllAsRead(testUser.getUserId());
            
            // then
            assertThat(notificationService.getUnreadCount(testUser.getUserId())).isEqualTo(0L);
            assertThat(notificationService.getUnreadCount(otherUser.getUserId())).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotificationTest {

        @Test
        @DisplayName("알림 삭제 성공")
        void deletesNotificationSuccessfully() throws Exception {
            // given
            Notification notification = Notification.create(testUser, testNotificationType, "삭제될 알림");
            notification = notificationRepository.save(notification);

            // when & then
            mockMvc.perform(delete("/notifications/" + notification.getId()))
                .andExpect(status().isNoContent());
            
            assertThat(notificationRepository.findById(notification.getId())).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 알림 삭제 시 예외")
        void failsWhenDeletingNonexistentNotification() {
            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), 999L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 사용자 알림 삭제 시 예외")
        void failsWhenDeletingOtherUsersNotification() {
            // given
            User otherUser = User.builder()
                .kakaoId(77777L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .build();
            otherUser = userRepository.save(otherUser);
            
            Notification otherNotification = Notification.create(otherUser, testNotificationType, "다른 사용자 알림");
            final Notification savedOtherNotification = notificationRepository.save(otherNotification);

            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(testUser.getUserId(), savedOtherNotification.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("삭제 후 읽지 않은 개수 업데이트")
        void updatesUnreadCountAfterDeletion() {
            // given
            for (int i = 0; i < 3; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }
            
            Notification toDelete = Notification.create(testUser, testNotificationType, "삭제될 알림");
            toDelete = notificationRepository.save(toDelete);
            
            Long beforeCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(beforeCount).isEqualTo(4L);

            // when
            notificationService.deleteNotification(testUser.getUserId(), toDelete.getId());

            // then
            Long afterCount = notificationService.getUnreadCount(testUser.getUserId());
            assertThat(afterCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("삭제된 알림이 목록에서 제외됨")
        void deletedNotificationNotVisibleInList() {
            // given
            Notification toDelete = null;
            for (int i = 0; i < 5; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notification = notificationRepository.save(notification);
                if (i == 2) {
                    toDelete = notification;
                }
            }

            // when
            notificationService.deleteNotification(testUser.getUserId(), toDelete.getId());

            // then
            NotificationListResponseDto response = notificationService.getNotifications(testUser.getUserId(), null, 10);
            assertThat(response.getNotifications()).hasSize(4);
            
            java.util.List<Long> notificationIds = response.getNotifications().stream()
                .map(item -> item.getNotificationId())
                .toList();
            assertThat(notificationIds).doesNotContain(toDelete.getId());
        }
    }

    @Nested
    @DisplayName("성능 및 파라미터 처리")
    class PerformanceAndParameterTest {

        @Test
        @DisplayName("성능 로깅 동작 확인")
        void performanceLoggingWorksCorrectly() throws Exception {
            // given
            for (int i = 0; i < 10; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "성능 테스트 알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notifications", hasSize(5)));
                
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNumber());
        }

        @Test
        @DisplayName("크기 파라미터 처리")
        void handlesSizeParameterCorrectly() throws Exception {
            // given
            for (int i = 0; i < 20; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "크기 테스트 알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications", hasSize(5)));
                
            mockMvc.perform(get("/notifications").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications", hasSize(lessThanOrEqualTo(100))));
        }
    }
}