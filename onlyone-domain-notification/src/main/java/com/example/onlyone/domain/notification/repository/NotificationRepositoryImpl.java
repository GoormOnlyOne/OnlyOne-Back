package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
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
    public List<NotificationItemDto> findNotificationsByUserId(Long userId, Long cursor, int size) {
        BooleanBuilder where = new BooleanBuilder()
                .and(notification.user.userId.eq(userId));

        if (cursor != null) {
            where.and(notification.id.lt(cursor));
        }

        return queryFactory
                .select(Projections.constructor(NotificationItemDto.class,
                        notification.id,
                        notification.content,
                        notification.type,
                        notification.isRead,
                        notification.createdAt))
                .from(notification)
                .where(where)
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
                        notification.isRead.eq(false))
                .fetchOne();

        return count != null ? count : 0L;
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
                        notification.isRead.eq(false))
                .execute();

        // 벌크 업데이트는 영속성 컨텍스트를 거치지 않으므로 동기화 필요
        entityManager.flush();
        entityManager.clear();
        return updated;
    }

    @Override
    @Transactional
    public void markSseSentByIds(List<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return;
        }
        queryFactory
                .update(notification)
                .set(notification.sseSent, true)
                .where(notification.id.in(notificationIds))
                .execute();
    }
}
