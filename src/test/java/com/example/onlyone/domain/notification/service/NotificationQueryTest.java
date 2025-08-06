package com.example.onlyone.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationListItem;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationQueryTest {

  @Mock
  private NotificationRepository notificationRepository;

  @InjectMocks
  private NotificationService notificationService;

  private Long userId;
  private List<NotificationListItem> mockNotifications;

  @BeforeEach
  void setUp() {
    userId = 1L;
    mockNotifications = createMockNotifications();
  }

  @Test
  @DisplayName("첫 페이지 조회 - 성공")
  void getNotifications_FirstPage_Success() {
    // given
    int size = 10;
    Long unreadCount = 5L;
    Pageable expectedPageable = PageRequest.of(0, size);

    when(notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, expectedPageable))
        .thenReturn(mockNotifications.subList(0, 5));
    when(notificationRepository.findAfterCursor(eq(userId), eq(5L), any(Pageable.class)))
        .thenReturn(mockNotifications.subList(5, 7)); // hasMore = true
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(unreadCount);

    // when
    NotificationListResponseDto result = notificationService.getNotifications(userId, null, size);

    // then
    assertThat(result.getNotifications()).hasSize(5);
    assertThat(result.getCursor()).isEqualTo(5L); // 마지막 알림의 ID
    assertThat(result.isHasMore()).isTrue();
    assertThat(result.getUnreadCount()).isEqualTo(unreadCount);

    verify(notificationRepository).findByUserIdOrderByNotificationIdDesc(userId, expectedPageable);
    verify(notificationRepository).findAfterCursor(eq(userId), eq(5L), any(Pageable.class));
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
  }

  @Test
  @DisplayName("커서 기반 조회 - 성공")
  void getNotifications_WithCursor_Success() {
    // given
    Long cursor = 10L;
    int size = 5;
    Long unreadCount = 3L;
    Pageable expectedPageable = PageRequest.of(0, size);

    when(notificationRepository.findAfterCursor(userId, cursor, expectedPageable))
        .thenReturn(mockNotifications.subList(0, 3));
    when(notificationRepository.findAfterCursor(eq(userId), eq(3L), any(Pageable.class)))
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

    verify(notificationRepository).findAfterCursor(userId, cursor, expectedPageable);
    verify(notificationRepository).findAfterCursor(eq(userId), eq(3L), any(Pageable.class));
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
  }

  @Test
  @DisplayName("페이지 크기 제한 - 100개 초과 시 제한 적용")
  void getNotifications_SizeLimit_Applied() {
    // given
    int requestSize = 150;
    int expectedSize = 100;
    Pageable expectedPageable = PageRequest.of(0, expectedSize);

    when(notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, expectedPageable))
        .thenReturn(Collections.emptyList());
    when(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
        .thenReturn(0L);

    // when
    notificationService.getNotifications(userId, null, requestSize);

    // then
    verify(notificationRepository).findByUserIdOrderByNotificationIdDesc(userId, expectedPageable);
  }

  @Test
  @DisplayName("빈 결과 조회 - 성공")
  void getNotifications_EmptyResult_Success() {
    // given
    int size = 10;
    Long unreadCount = 0L;
    Pageable expectedPageable = PageRequest.of(0, size);

    when(notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, expectedPageable))
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

    verify(notificationRepository).findByUserIdOrderByNotificationIdDesc(userId, expectedPageable);
    verify(notificationRepository).countByUser_UserIdAndIsReadFalse(userId);
    verify(notificationRepository, never()).findAfterCursor(any(), any(), any());
  }

  @Test
  @DisplayName("마지막 페이지 조회 - hasMore false")
  void getNotifications_LastPage_HasMoreFalse() {
    // given
    Long cursor = 20L;
    int size = 10;
    List<NotificationListItem> lastPageItems = mockNotifications.subList(0, 3);

    when(notificationRepository.findAfterCursor(userId, cursor, PageRequest.of(0, size)))
        .thenReturn(lastPageItems);
    when(notificationRepository.findAfterCursor(eq(userId), eq(3L), any(Pageable.class)))
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
    List<NotificationListItem> exactSizeItems = mockNotifications.subList(0, 5);

    when(notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, PageRequest.of(0, size)))
        .thenReturn(exactSizeItems);
    when(notificationRepository.findAfterCursor(eq(userId), eq(5L), any(Pageable.class)))
        .thenReturn(mockNotifications.subList(5, 6));
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

  private List<NotificationListItem> createMockNotifications() {
    return List.of(
        createNotificationListItem(1L, "새로운 채팅 메시지가 도착했습니다.", Type.CHAT, false),
        createNotificationListItem(2L, "정산이 완료되었습니다.", Type.SETTLEMENT, true),
        createNotificationListItem(3L, "게시글이 좋아요를 받았습니다.", Type.LIKE, false),
        createNotificationListItem(4L, "새로운 댓글이 등록되었습니다.", Type.COMMENT, false),
        createNotificationListItem(5L, "채팅방에 새로운 참가자가 추가되었습니다.", Type.CHAT, true),
        createNotificationListItem(6L, "정산 요청이 승인되었습니다.", Type.SETTLEMENT, false),
        createNotificationListItem(7L, "댓글이 좋아요를 받았습니다.", Type.LIKE, false),
        createNotificationListItem(8L, "댓글에 답글이 달렸습니다.", Type.COMMENT, true),
        createNotificationListItem(9L, "채팅 메시지가 삭제되었습니다.", Type.CHAT, false),
        createNotificationListItem(10L, "정산이 취소되었습니다.", Type.SETTLEMENT, false)
    );
  }

  private NotificationListItem createNotificationListItem(Long id, String content, Type type, boolean isRead) {
    return NotificationListItem.builder()
        .notificationId(id)
        .content(content)
        .type(type)
        .isRead(isRead)
        .createdAt(LocalDateTime.now().minusHours(id))
        .build();
  }
}