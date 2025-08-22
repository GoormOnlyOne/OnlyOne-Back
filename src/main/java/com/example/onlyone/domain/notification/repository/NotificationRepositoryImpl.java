package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.Type;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.onlyone.domain.notification.entity.QAppNotification.appNotification;
import static com.example.onlyone.domain.notification.entity.QNotificationType.notificationType;
import static com.example.onlyone.domain.user.entity.QUser.user;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {
    
    private final JPAQueryFactory queryFactory;
    
    @Override
    public List<NotificationItemDto> findNotificationsByUserId(
            Long userId, 
            Long cursor, 
            int size
    ) {
        return queryFactory
                .select(Projections.constructor(NotificationItemDto.class,
                        appNotification.id,
                        appNotification.content,
                        notificationType.type,
                        appNotification.isRead,
                        appNotification.createdAt,
                        new CaseBuilder()
                                .when(notificationType.type.eq(Type.CHAT)).then("CHAT")
                                .when(notificationType.type.eq(Type.SETTLEMENT)).then("SETTLEMENT")
                                .when(notificationType.type.eq(Type.LIKE)).then("POST")
                                .when(notificationType.type.eq(Type.COMMENT)).then("POST")
                                .when(notificationType.type.eq(Type.REFEED)).then("FEED")
                                .otherwise("UNKNOWN"),
                        Expressions.nullExpression(Long.class) // targetId 제거
                ))
                .from(appNotification)
                .join(appNotification.notificationType, notificationType)
                .where(
                        appNotification.user.userId.eq(userId),
                        cursorCondition(cursor)
                )
                .orderBy(appNotification.id.desc())
                .limit(size)
                .fetch();
    }
    
    @Override
    public List<NotificationItemDto> findNotificationsByUserIdAndType(
            Long userId, 
            Type type,
            Long cursor,
            int size
    ) {
        return queryFactory
                .select(Projections.constructor(NotificationItemDto.class,
                        appNotification.id,
                        appNotification.content,
                        notificationType.type,
                        appNotification.isRead,
                        appNotification.createdAt,
                        new CaseBuilder()
                                .when(notificationType.type.eq(Type.CHAT)).then("CHAT")
                                .when(notificationType.type.eq(Type.SETTLEMENT)).then("SETTLEMENT")
                                .when(notificationType.type.eq(Type.LIKE)).then("POST")
                                .when(notificationType.type.eq(Type.COMMENT)).then("POST")
                                .when(notificationType.type.eq(Type.REFEED)).then("FEED")
                                .otherwise("UNKNOWN"),
                        Expressions.nullExpression(Long.class) // targetId 제거
                ))
                .from(appNotification)
                .join(appNotification.notificationType, notificationType)
                .where(
                        appNotification.user.userId.eq(userId),
                        notificationType.type.eq(type),
                        cursorCondition(cursor)
                )
                .orderBy(appNotification.id.desc())
                .limit(size)
                .fetch();
    }
    
    @Override
    public Long countUnreadByUserId(Long userId) {
        return queryFactory
                .select(appNotification.count())
                .from(appNotification)
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.isRead.eq(false)
                )
                .fetchOne();
    }
    
    @Override
    public List<AppNotification> findUnreadNotificationsByUserId(Long userId) {
        return queryFactory
                .selectFrom(appNotification)
                .join(appNotification.notificationType, notificationType).fetchJoin()
                .join(appNotification.user, user).fetchJoin()
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.isRead.eq(false)
                )
                .orderBy(appNotification.createdAt.desc())
                .fetch();
    }
    
    @Override
    public List<AppNotification> findFailedFcmNotificationsByUserId(Long userId) {
        return queryFactory
                .selectFrom(appNotification)
                .join(appNotification.notificationType, notificationType).fetchJoin()
                .join(appNotification.user, user).fetchJoin()
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.fcmSent.eq(false),
                        appNotification.notificationType.deliveryMethod.in(
                                com.example.onlyone.domain.notification.entity.DeliveryMethod.FCM_ONLY,
                                com.example.onlyone.domain.notification.entity.DeliveryMethod.BOTH
                        )
                )
                .orderBy(appNotification.createdAt.desc())
                .fetch();
    }
    
    @Override
    public List<AppNotification> findNotificationsByUserIdAfter(
            Long userId, 
            LocalDateTime after
    ) {
        return queryFactory
                .selectFrom(appNotification)
                .join(appNotification.notificationType, notificationType).fetchJoin()
                .join(appNotification.user, user).fetchJoin()
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.createdAt.after(after)
                )
                .orderBy(appNotification.createdAt.asc())
                .fetch();
    }
    
    @Override
    public AppNotification findByIdWithFetchJoin(Long notificationId) {
        return queryFactory
                .selectFrom(appNotification)
                .join(appNotification.notificationType, notificationType).fetchJoin()
                .join(appNotification.user, user).fetchJoin()
                .where(appNotification.id.eq(notificationId))
                .fetchOne();
    }
    
    @Override
    public NotificationStats getNotificationStats(Long userId) {
        Long totalCount = queryFactory
                .select(appNotification.count())
                .from(appNotification)
                .where(appNotification.user.userId.eq(userId))
                .fetchOne();
        
        Long unreadCount = queryFactory
                .select(appNotification.count())
                .from(appNotification)
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.isRead.eq(false)
                )
                .fetchOne();
        
        Long fcmSentCount = queryFactory
                .select(appNotification.count())
                .from(appNotification)
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.fcmSent.eq(true)
                )
                .fetchOne();
        
        Long fcmFailedCount = queryFactory
                .select(appNotification.count())
                .from(appNotification)
                .join(appNotification.notificationType, notificationType)
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.fcmSent.eq(false),
                        appNotification.notificationType.deliveryMethod.in(
                                com.example.onlyone.domain.notification.entity.DeliveryMethod.FCM_ONLY,
                                com.example.onlyone.domain.notification.entity.DeliveryMethod.BOTH
                        )
                )
                .fetchOne();
        
        return new NotificationStats(
                totalCount != null ? totalCount : 0L,
                unreadCount != null ? unreadCount : 0L,
                fcmSentCount != null ? fcmSentCount : 0L,
                fcmFailedCount != null ? fcmFailedCount : 0L
        );
    }
    
    @Override
    @Transactional
    public long markAllAsReadByUserId(Long userId) {
        return queryFactory
                .update(appNotification)
                .set(appNotification.isRead, true)
                .where(
                        appNotification.user.userId.eq(userId),
                        appNotification.isRead.eq(false)
                )
                .execute();
    }
    
    @Override
    @Transactional
    public long deleteOldNotifications(int daysToKeep) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        
        return queryFactory
                .delete(appNotification)
                .where(
                        appNotification.createdAt.before(cutoffDate),
                        appNotification.isRead.eq(true)
                )
                .execute();
    }

    @Override
    @Transactional
    public void batchInsertNotifications(List<AppNotification> notifications) {
        if (notifications.isEmpty()) {
            return;
        }
        
        // 배치 크기 설정 (보통 1000개씩)
        int batchSize = 1000;
        for (int i = 0; i < notifications.size(); i += batchSize) {
            int end = Math.min(i + batchSize, notifications.size());
            List<AppNotification> batch = notifications.subList(i, end);
            
            // QueryDSL로 배치 삽입
            for (AppNotification notification : batch) {
                queryFactory
                    .insert(appNotification)
                    .set(appNotification.content, notification.getContent())
                    .set(appNotification.isRead, notification.isRead())
                    .set(appNotification.fcmSent, notification.isFcmSent())
                    .set(appNotification.user.userId, notification.getUser().getUserId())
                    .set(appNotification.notificationType.id, notification.getNotificationType().getId())
                    .execute();
            }
        }
    }

    private BooleanExpression cursorCondition(Long cursor) {
        return cursor == null ? null : appNotification.id.lt(cursor);
    }
}