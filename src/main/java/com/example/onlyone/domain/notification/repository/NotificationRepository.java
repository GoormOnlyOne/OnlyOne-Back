package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.NotificationListItem;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.awt.print.Pageable;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 사용자 별로 알림을 최신순으로 정렬해서 조회
    List<Notification> findByUserOrderByCreatedAtDesc(User user);


    // 사용자의 읽지 않은 알림 조회
    List<Notification> findByUser_UserIdAndIsReadFalse(Long userId);


    // 첫 페이지
    @Query("""
    select new com.example.onlyone.domain.notification.dto.NotificationListItem(
      n.notificationId, n.content, n.notificationType.type, n.isRead, n.createdAt
    )
    from Notification n
    where n.user.userId = :userId
    order by n.notificationId desc
    """)
    List<NotificationListItem> findTopByUser(Long userId, Pageable pageable);

    // 두 번째 이후 페이지
    @Query("""
    select new com.example.onlyone.domain.notification.dto.NotificationListItem(
      n.notificationId, n.content, n.notificationType.type, n.isRead, n.createdAt
    )
    from Notification n
    where n.user.userId = :userId
      and n.notificationId < :cursor
    order by n.notificationId desc
    """)
    List<NotificationListItem> findAfterCursor(Long userId, Long cursor, Pageable pageable);

    // 전체 미읽음 카운트
    long countByUser_UserIdAndIsReadFalse(Long userId);
}
