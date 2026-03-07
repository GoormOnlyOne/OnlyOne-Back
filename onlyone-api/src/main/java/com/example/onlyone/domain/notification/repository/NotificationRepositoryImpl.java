package com.example.onlyone.domain.notification.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    private final EntityManager entityManager;

    @Override
    @Transactional
    public int markAsReadByIdAndUserId(Long notificationId, Long userId) {
        return entityManager
                .createQuery("UPDATE Notification n SET n.isRead = true " +
                        "WHERE n.id = :id AND n.user.userId = :userId AND n.isRead = false")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean deleteByIdAndUserId(Long notificationId, Long userId) {
        // 삭제 전 읽음 상태 확인 (단일 쿼리로 조회 + 삭제 통합 불가하므로 SELECT 1회 + DELETE 1회)
        List<?> result = entityManager
                .createQuery("SELECT n.isRead FROM Notification n " +
                        "WHERE n.id = :id AND n.user.userId = :userId")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .getResultList();

        if (result.isEmpty()) return false;

        boolean wasUnread = Boolean.FALSE.equals(result.get(0));

        entityManager
                .createQuery("DELETE FROM Notification n " +
                        "WHERE n.id = :id AND n.user.userId = :userId")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();

        return wasUnread;
    }

    /**
     * 워터마크 방식 mark-all: notification 테이블 대량 UPDATE 없음.
     * user_notification_state에 현재 최대 notification_id를 upsert.
     * GREATEST로 동시 호출 시에도 단조 증가 보장.
     * NOTE: ON DUPLICATE KEY UPDATE는 MySQL 전용 구문이나, 워터마크 upsert 패턴에 필수.
     */
    @Override
    @Transactional
    public long markAllAsReadByUserId(Long userId) {
        Object maxIdResult = entityManager
                .createNativeQuery(
                        "SELECT COALESCE(MAX(notification_id), 0) " +
                        "FROM notification WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult();

        long maxId = ((Number) maxIdResult).longValue();
        if (maxId == 0) return 0;

        entityManager
                .createNativeQuery(
                        "INSERT INTO user_notification_state(user_id, read_all_upto_id, updated_at) " +
                        "VALUES (:userId, :maxId, NOW(6)) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "read_all_upto_id = GREATEST(read_all_upto_id, VALUES(read_all_upto_id)), " +
                        "updated_at = NOW(6)")
                .setParameter("userId", userId)
                .setParameter("maxId", maxId)
                .executeUpdate();

        return 1;
    }

    @Override
    @Transactional
    public void markDeliveredByIds(List<Long> notificationIds) {
        if (notificationIds.isEmpty()) return;
        entityManager
                .createQuery("UPDATE Notification n SET n.sseSent = true " +
                        "WHERE n.id IN :ids")
                .setParameter("ids", notificationIds)
                .executeUpdate();
    }
}
