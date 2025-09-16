package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.onlyone.domain.notification.entity.QNotification.notification;
import static com.example.onlyone.domain.notification.entity.QNotificationType.notificationType;
import static com.example.onlyone.domain.user.entity.QUser.user;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {
    
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;
    
    @Override
    public List<NotificationItemDto> findNotificationsByUserId(
            Long userId, 
            Long cursor, 
            int size
    ) {

        return queryFactory
                .select(Projections.constructor(NotificationItemDto.class,
                        notification.id,
                        notification.content,
                        notificationType.type,
                        notification.isRead,
                        notification.createdAt))
                .from(notification)
                .join(notification.notificationType, notificationType)
                .where(
                        notification.user.userId.eq(userId),
                        cursorCondition(cursor)
                )
                .orderBy(notification.id.desc())
                .limit(size)
                .fetch();
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
    public Notification findByIdWithFetchJoin(Long notificationId) {
        return queryFactory
                .selectFrom(notification)
                .join(notification.notificationType, notificationType).fetchJoin()
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
        entityManager.clear();
        return updated;
    }
    
    @Override
    @Transactional
    public long updateSseSentStatus(Long notificationId, boolean sent) {
        long updated = queryFactory
                .update(notification)
                .set(notification.sseSent, sent)
                .where(notification.id.eq(notificationId))
                .execute();
        entityManager.clear();
        return updated;
    }
    

    @Override
    public List<Notification> findUnsentNotificationsByUserId(Long userId) {
        try {

            return queryFactory
                    .selectFrom(notification)
                    .join(notification.notificationType, notificationType).fetchJoin()
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
                    .join(notification.notificationType, notificationType).fetchJoin()
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