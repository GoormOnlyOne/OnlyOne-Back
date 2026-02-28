package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    private final EntityManager entityManager;

    @Override
    public List<NotificationItemDto> findNotificationsByUserId(Long userId, Long cursor, int size) {
        // 네이티브 쿼리 — User JOIN 제거, idx_notification_user_id_desc 인덱스 직접 활용
        String sql = cursor != null
                ? "SELECT notification_id, content, type, is_read, created_at FROM notification WHERE user_id = :userId AND notification_id < :cursor ORDER BY notification_id DESC LIMIT :limit"
                : "SELECT notification_id, content, type, is_read, created_at FROM notification WHERE user_id = :userId ORDER BY notification_id DESC LIMIT :limit";

        Query query = entityManager.createNativeQuery(sql)
                .setParameter("userId", userId)
                .setParameter("limit", size);

        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapToNotificationItems(rows);
    }

    @Override
    public Long countUnreadByUserId(Long userId) {
        // idx_notification_user_read 커버링 인덱스 활용
        Object result = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM notification WHERE user_id = :userId AND is_read = false")
                .setParameter("userId", userId)
                .getSingleResult();

        return result instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    @Transactional
    public int markAsReadByIdAndUserId(Long notificationId, Long userId) {
        // 소유권 검증 + 읽음 처리를 단일 쿼리로 — SELECT + UPDATE 대신 UPDATE 1회
        return entityManager
                .createNativeQuery("UPDATE notification SET is_read = true WHERE notification_id = :id AND user_id = :userId AND is_read = false")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean deleteByIdAndUserId(Long notificationId, Long userId) {
        // 단일 쿼리: 미읽음 알림 삭제 시도 → 성공하면 wasUnread=true
        int deletedUnread = entityManager
                .createNativeQuery("DELETE FROM notification WHERE notification_id = :id AND user_id = :userId AND is_read = false")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();

        if (deletedUnread > 0) return true;

        // 미읽음이 아니었으면 읽음 상태 알림 삭제 (wasUnread=false)
        int deletedRead = entityManager
                .createNativeQuery("DELETE FROM notification WHERE notification_id = :id AND user_id = :userId")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();

        // deletedRead == 0이면 알림 자체가 없음 — 기존 동작과 동일하게 false 반환
        return false;
    }

    @Override
    @Transactional
    public long markAllAsReadByUserId(Long userId) {
        // LIMIT 배치 분할: 1,000건씩 UPDATE → row lock 점유 시간 최소화
        long totalUpdated = 0;
        int batchSize = 1000;
        int updated;

        do {
            updated = entityManager
                    .createNativeQuery("UPDATE notification SET is_read = true WHERE user_id = :userId AND is_read = false LIMIT :batchSize")
                    .setParameter("userId", userId)
                    .setParameter("batchSize", batchSize)
                    .executeUpdate();
            totalUpdated += updated;

            if (updated > 0) {
                entityManager.flush();
                entityManager.clear();
            }
        } while (updated == batchSize);

        return totalUpdated;
    }

    @Override
    public List<NotificationItemDto> findUnsentNotificationsByUserId(Long userId, int limit) {
        // 네이티브 쿼리 — User JOIN 제거, idx_notification_user_sse_sent 인덱스 직접 활용
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT notification_id, content, type, is_read, created_at FROM notification WHERE user_id = :userId AND sse_sent = false ORDER BY notification_id ASC LIMIT :limit")
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return mapToNotificationItems(rows);
    }

    @Override
    @Transactional
    public void markSseSentByIds(List<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return;
        }
        entityManager
                .createNativeQuery("UPDATE notification SET sse_sent = true WHERE notification_id IN (:ids)")
                .setParameter("ids", notificationIds)
                .executeUpdate();
    }

    private List<NotificationItemDto> mapToNotificationItems(List<Object[]> rows) {
        List<NotificationItemDto> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new NotificationItemDto(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    NotificationType.valueOf((String) row[2]),
                    toBoolean(row[3]),
                    ((Timestamp) row[4]).toLocalDateTime()
            ));
        }
        return result;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }
}
