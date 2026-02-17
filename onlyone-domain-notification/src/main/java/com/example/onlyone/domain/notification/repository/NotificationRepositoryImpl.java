package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.onlyone.domain.notification.entity.QNotification.notification;
import static com.example.onlyone.domain.user.entity.QUser.user;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {
    
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;
    
    @Override
    @SuppressWarnings("unchecked")
    public List<NotificationItemDto> findNotificationsByUserId(
            Long userId,
            Long cursor,
            int size
    ) {
        String sql = "SELECT notification_id, content, type, is_read, created_at " +
                "FROM notification USE INDEX(idx_notification_user_id_desc) " +
                "WHERE user_id = :userId" +
                (cursor != null ? " AND notification_id < :cursor" : "") +
                " ORDER BY notification_id DESC LIMIT :size";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userId", userId);
        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }
        query.setParameter("size", size);

        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new NotificationItemDto(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        NotificationType.valueOf((String) row[2]),
                        Boolean.TRUE.equals(row[3]) || (row[3] instanceof Number n && n.intValue() == 1),
                        row[4] instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) row[4]
                ))
                .toList();
    }
    
    
    @Override
    public Long countUnreadByUserId(Long userId) {
        Long count = queryFactory
                .select(notification.count())
                .from(notification)
                .where(
                        notification.user.userId.eq(userId),
                        notification.isRead.eq(false)
                )
                .fetchOne();
        
        return count != null ? count : 0L;
    }
    
    @Override
    public List<Notification> findUnreadNotificationsByUserId(Long userId) {
        return queryFactory
                .selectFrom(notification)
                .join(notification.user, user).fetchJoin()
                .where(
                        notification.user.userId.eq(userId),
                        notification.isRead.eq(false)
                )
                .orderBy(notification.createdAt.desc())
                .fetch();
    }
    
    @Override
    public Notification findByIdWithFetchJoin(Long notificationId) {
        return queryFactory
                .selectFrom(notification)
                .join(notification.user, user).fetchJoin()
                .where(notification.id.eq(notificationId))
                .fetchOne();
    }
    
    @Override
    @Transactional
    public long markAllAsReadByUserId(Long userId) {
        long updated = queryFactory
                .update(notification)
                .set(notification.isRead, true)
                .where(
                        notification.user.userId.eq(userId),
                        notification.isRead.eq(false)
                )
                .execute();

        entityManager.flush();
        entityManager.clear(); // 벌크 업데이트 후 1차 캐시 무효화
        return updated;
    }

    @Override
    public List<Notification> findUnsentNotificationsByUserId(Long userId) {
        try {
            return queryFactory
                    .selectFrom(notification)
                    .join(notification.user, user).fetchJoin()
                    .where(
                            notification.user.userId.eq(userId),
                            notification.sseSent.eq(false)
                    )
                    .orderBy(notification.createdAt.asc())
                    .limit(20)
                    .fetch();
        } catch (Exception e) {
            log.error("Error fetching unsent notifications for userId: {}", userId, e);
            return List.of();
        }
    }
    
    @Override
    public List<Notification> findUnsentNotificationsByUserIdAfterTime(Long userId, LocalDateTime afterTime) {
        try {
            return queryFactory
                    .selectFrom(notification)
                    .join(notification.user, user).fetchJoin()
                    .where(
                            notification.user.userId.eq(userId),
                            notification.sseSent.eq(false),
                            notification.createdAt.gt(afterTime)
                    )
                    .orderBy(notification.createdAt.asc())
                    .limit(15)
                    .fetch();
        } catch (Exception e) {
            log.error("Error fetching unsent notifications for userId: {} after time: {}", userId, afterTime, e);
            return List.of();
        }
    }

    private BooleanExpression cursorCondition(Long cursor) {
        return cursor == null ? null : notification.id.lt(cursor);
    }
}