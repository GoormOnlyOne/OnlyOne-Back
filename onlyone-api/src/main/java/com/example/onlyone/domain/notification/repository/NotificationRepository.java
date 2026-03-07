package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemProjection;
import com.example.onlyone.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long>, NotificationRepositoryCustom {

    // ── 알림 목록 (cursor 없음) ── 워터마크 반영 is_read (LEFT JOIN)
    @Query(value =
            "SELECT n.notification_id AS notificationId, n.content AS content, n.type AS type, " +
            "(n.is_read = true OR n.notification_id <= COALESCE(uns.read_all_upto_id, 0)) AS isRead, " +
            "n.created_at AS createdAt " +
            "FROM notification n " +
            "LEFT JOIN user_notification_state uns ON uns.user_id = n.user_id " +
            "WHERE n.user_id = :userId " +
            "ORDER BY n.notification_id DESC LIMIT :limit",
            nativeQuery = true)
    List<NotificationItemProjection> findNotificationsByUserId(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    // ── 알림 목록 (cursor 있음) ── 워터마크 반영 is_read (LEFT JOIN)
    @Query(value =
            "SELECT n.notification_id AS notificationId, n.content AS content, n.type AS type, " +
            "(n.is_read = true OR n.notification_id <= COALESCE(uns.read_all_upto_id, 0)) AS isRead, " +
            "n.created_at AS createdAt " +
            "FROM notification n " +
            "LEFT JOIN user_notification_state uns ON uns.user_id = n.user_id " +
            "WHERE n.user_id = :userId AND n.notification_id < :cursor " +
            "ORDER BY n.notification_id DESC LIMIT :limit",
            nativeQuery = true)
    List<NotificationItemProjection> findNotificationsByUserIdWithCursor(
            @Param("userId") Long userId,
            @Param("cursor") Long cursor,
            @Param("limit") int limit);

    // ── unread 카운트 ── 워터마크 반영 (LEFT JOIN)
    @Query(value =
            "SELECT COUNT(*) FROM notification n " +
            "LEFT JOIN user_notification_state uns ON uns.user_id = n.user_id " +
            "WHERE n.user_id = :userId AND n.is_read = false " +
            "AND n.notification_id > COALESCE(uns.read_all_upto_id, 0)",
            nativeQuery = true)
    long countUnreadByUserId(@Param("userId") Long userId);

    // ── 미전송 알림 조회 (SSE recovery) ──
    @Query(value =
            "SELECT n.notification_id AS notificationId, n.content AS content, n.type AS type, " +
            "(n.is_read = true) AS isRead, n.created_at AS createdAt " +
            "FROM notification n WHERE n.user_id = :userId AND n.sse_sent = false " +
            "ORDER BY n.notification_id ASC LIMIT :limit",
            nativeQuery = true)
    List<NotificationItemProjection> findUndeliveredByUserId(
            @Param("userId") Long userId,
            @Param("limit") int limit);
}
