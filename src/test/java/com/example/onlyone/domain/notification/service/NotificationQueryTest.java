package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationRepository.NotificationListProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 네이티브 쿼리 기반 NotificationQueryTest
 */
@ExtendWith(MockitoExtension.class)
class NotificationQueryTest {

  @Mock
  private NotificationRepository notificationRepository;

  @InjectMocks
  private NotificationService notificationService;

  private Long userId;
  private List<NotificationListProjection> mockProjections;

  @BeforeEach
  void setUp() {
    userId = 1L;
    mockProjections = createMockProjections();
  }

  @Test
  @DisplayName("첫 페이지 조회 - 성공")
  void getNotifications_FirstPage_Success() {
    // given
    int size = 10;
    Long unreadCount = 5L;

    when(notificationRepository.findFirstPageByUserId(userId, size))
        .thenReturn(mockProjections.subList(0, 5));
    when(notificationRepository.findAfterCursorByUserId(userId, 5L, 1))
        .thenReturn(mockProjections.subList(5, 6)); // hasMore = true
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(unreadCount);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, null, size);

    // then
    assertThat(result.getNotifications()).hasSize(5);
    assertThat(result.getCursor()).isEqualTo(5L); // 마지막 알림의 ID
    assertThat(result.isHasMore()).isTrue();
    assertThat(result.getUnreadCount()).isEqualTo(unreadCount);

    verify(notificationRepository).findFirstPageByUserId(userId, size);
    verify(notificationRepository).findAfterCursorByUserId(userId, 5L, 1);
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
  }

  @Test
  @DisplayName("커서 기반 조회 - 성공")
  void getNotifications_WithCursor_Success() {
    // given
    Long cursor = 10L;
    int size = 5;
    Long unreadCount = 3L;

    when(notificationRepository.findAfterCursorByUserId(userId, cursor, size))
        .thenReturn(mockProjections.subList(0, 3));
    when(notificationRepository.findAfterCursorByUserId(userId, 3L, 1))
        .thenReturn(Collections.emptyList()); // hasMore = false
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(unreadCount);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, cursor, size);

    // then
    assertThat(result.getNotifications()).hasSize(3);
    assertThat(result.getCursor()).isEqualTo(3L);
    assertThat(result.isHasMore()).isFalse();
    assertThat(result.getUnreadCount()).isEqualTo(unreadCount);

    verify(notificationRepository).findAfterCursorByUserId(userId, cursor, size);
    verify(notificationRepository).findAfterCursorByUserId(userId, 3L, 1);
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
  }

  @Test
  @DisplayName("페이지 크기 제한 - 100개 초과 시 제한 적용")
  void getNotifications_SizeLimit_Applied() {
    // given
    int requestSize = 150;
    int expectedSize = 100;

    when(notificationRepository.findFirstPageByUserId(userId, expectedSize))
        .thenReturn(Collections.emptyList());
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(0L);

    // when
    notificationService.getNotifications(userId, null, requestSize);

    // then
    verify(notificationRepository).findFirstPageByUserId(userId, expectedSize);
  }

  @Test
  @DisplayName("빈 결과 조회 - 성공")
  void getNotifications_EmptyResult_Success() {
    // given
    int size = 10;
    Long unreadCount = 0L;

    when(notificationRepository.findFirstPageByUserId(userId, size))
        .thenReturn(Collections.emptyList());
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(unreadCount);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, null, size);

    // then
    assertThat(result.getNotifications()).isEmpty();
    assertThat(result.getCursor()).isNull();
    assertThat(result.isHasMore()).isFalse();
    assertThat(result.getUnreadCount()).isEqualTo(unreadCount);

    verify(notificationRepository).findFirstPageByUserId(userId, size);
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
    verify(notificationRepository, never()).findAfterCursorByUserId(eq(userId), eq(null), eq(1));
  }

  @Test
  @DisplayName("마지막 페이지 조회 - hasMore false")
  void getNotifications_LastPage_HasMoreFalse() {
    // given
    Long cursor = 20L;
    int size = 10;
    List<NotificationListProjection> lastPageItems = mockProjections.subList(0, 3);

    when(notificationRepository.findAfterCursorByUserId(userId, cursor, size))
        .thenReturn(lastPageItems);
    when(notificationRepository.findAfterCursorByUserId(userId, 3L, 1))
        .thenReturn(Collections.emptyList());
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(1L);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, cursor, size);

    // then
    assertThat(result.getNotifications()).hasSize(3);
    assertThat(result.getCursor()).isEqualTo(3L);
    assertThat(result.isHasMore()).isFalse();
    assertThat(result.getUnreadCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("정확한 페이지 크기로 hasMore true")
  void getNotifications_ExactPageSize_HasMoreTrue() {
    // given
    int size = 5;
    List<NotificationListProjection> exactSizeItems = mockProjections.subList(0, 5);

    when(notificationRepository.findFirstPageByUserId(userId, size))
        .thenReturn(exactSizeItems);
    when(notificationRepository.findAfterCursorByUserId(userId, 5L, 1))
        .thenReturn(mockProjections.subList(5, 6));
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(10L);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, null, size);

    // then
    assertThat(result.getNotifications()).hasSize(5);
    assertThat(result.getCursor()).isEqualTo(5L);
    assertThat(result.isHasMore()).isTrue();
    assertThat(result.getUnreadCount()).isEqualTo(10L);
  }

  // ================================
  // Helper Methods
  // ================================

  private List<NotificationListProjection> createMockProjections() {
    return List.of(
        createMockProjection(1L, "새로운 채팅 메시지가 도착했습니다.", Type.CHAT, false),
        createMockProjection(2L, "정산이 완료되었습니다.", Type.SETTLEMENT, true),
        createMockProjection(3L, "게시글이 좋아요를 받았습니다.", Type.LIKE, false),
        createMockProjection(4L, "새로운 댓글이 등록되었습니다.", Type.COMMENT, false),
        createMockProjection(5L, "채팅방에 새로운 참가자가 추가되었습니다.", Type.CHAT, true),
        createMockProjection(6L, "정산 요청이 승인되었습니다.", Type.SETTLEMENT, false),
        createMockProjection(7L, "댓글이 좋아요를 받았습니다.", Type.LIKE, false),
        createMockProjection(8L, "댓글에 답글이 달렸습니다.", Type.COMMENT, true),
        createMockProjection(9L, "채팅 메시지가 삭제되었습니다.", Type.CHAT, false),
        createMockProjection(10L, "정산이 취소되었습니다.", Type.SETTLEMENT, false)
    );
  }

  private NotificationListProjection createMockProjection(Long id, String content, Type type, boolean isRead) {
    return new NotificationListProjection() {
      @Override
      public Long getNotificationId() {
        return id;
      }

      @Override
      public String getContent() {
        return content;
      }

      @Override
      public String getType() {
        return type.name();
      }

      @Override
      public Boolean getIsRead() {
        return isRead;
      }

      @Override
      public LocalDateTime getCreatedAt() {
        return LocalDateTime.now().minusHours(id);
      }
    };
  }
}