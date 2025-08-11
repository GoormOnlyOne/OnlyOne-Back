package com.example.onlyone.domain.notification.repository;


import com.example.onlyone.domain.notification.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 알림 리포지토리 - 네이티브 쿼리 사용
 */
public interface NotificationRepository extends JpaRepository<AppNotification, Long> {

  /**
   * 사용자의 읽지 않은 알림 목록 조회 (엔티티 전체)
   */
  List<AppNotification> findByUser_UserIdAndIsReadFalse(Long userId);

  /**
   * 사용자의 읽지 않은 알림 개수 조회
   */
  @Query(value = """
        SELECT COUNT(*) 
        FROM notification n 
        WHERE n.user_id = :userId 
          AND n.is_read = false
        """, nativeQuery = true)
  long countByUser_UserIdAndIsReadFalse(@Param("userId") Long userId);

  /**
   * 특정 시간 이후 생성된 알림 목록 조회 (SSE Last-Event-ID용)
   */
  List<AppNotification> findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, LocalDateTime after);

  /**
   * FCM 전송 실패한 알림 목록 조회
   */
  List<AppNotification> findByUser_UserIdAndFcmSentFalse(Long userId);

  /**
   * 첫 페이지 알림 목록 조회 (네이티브 쿼리)
   */
  @Query(value = """
        SELECT 
            n.notification_id as notificationId,
            n.content as content,
            nt.type as type,
            n.is_read as isRead,
            n.created_at as createdAt
        FROM notification n
        INNER JOIN notification_type nt ON n.type_id = nt.type_id
        WHERE n.user_id = :userId
        ORDER BY n.notification_id DESC
        LIMIT :limit
        """, nativeQuery = true)
  List<NotificationListProjection> findFirstPageByUserId(
      @Param("userId") Long userId,
      @Param("limit") int limit
  );

  /**
   * 커서 이후 알림 목록 조회 (네이티브 쿼리)
   */
  @Query(value = """
        SELECT 
            n.notification_id as notificationId,
            n.content as content,
            nt.type as type,
            n.is_read as isRead,
            n.created_at as createdAt
        FROM notification n
        INNER JOIN notification_type nt ON n.type_id = nt.type_id
        WHERE n.user_id = :userId
          AND n.notification_id < :cursor
        ORDER BY n.notification_id DESC
        LIMIT :limit
        """, nativeQuery = true)
  List<NotificationListProjection> findAfterCursorByUserId(
      @Param("userId") Long userId,
      @Param("cursor") Long cursor,
      @Param("limit") int limit
  );

  /**
   * 네이티브 쿼리 결과를 위한 프로젝션 인터페이스
   */
  interface NotificationListProjection {
    Long getNotificationId();
    String getContent();
    String getType();
    Boolean getIsRead();
    LocalDateTime getCreatedAt();
  }
}