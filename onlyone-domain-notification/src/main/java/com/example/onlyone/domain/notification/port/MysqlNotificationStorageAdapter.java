package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemProjection;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.storage", havingValue = "mysql", matchIfMissing = true)
public class MysqlNotificationStorageAdapter implements NotificationStoragePort {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Override
    public Long save(Long userId, NotificationType type, String content) {
        User user = userRepository.getReferenceById(userId);
        Notification notification = Notification.createWithContent(user, type, content);
        notificationRepository.save(notification);
        return notification.getId();
    }

    @Override
    public List<NotificationItemDto> findByUserId(Long userId, Long cursor, int size) {
        List<NotificationItemProjection> projections = (cursor != null)
                ? notificationRepository.findNotificationsByUserIdWithCursor(userId, cursor, size)
                : notificationRepository.findNotificationsByUserId(userId, size);
        return projections.stream().map(this::toDto).toList();
    }

    @Override
    public Long countUnreadByUserId(Long userId) {
        return notificationRepository.countUnreadByUserId(userId);
    }

    @Override
    public int markAsReadByIdAndUserId(Long notificationId, Long userId) {
        return notificationRepository.markAsReadByIdAndUserId(notificationId, userId);
    }

    @Override
    public boolean deleteByIdAndUserId(Long notificationId, Long userId) {
        return notificationRepository.deleteByIdAndUserId(notificationId, userId);
    }

    @Override
    public long markAllAsReadByUserId(Long userId) {
        return notificationRepository.markAllAsReadByUserId(userId);
    }

    @Override
    public void markDeliveredByIds(List<Long> notificationIds) {
        notificationRepository.markDeliveredByIds(notificationIds);
    }

    @Override
    public List<NotificationItemDto> findUndeliveredByUserId(Long userId, int limit) {
        return notificationRepository.findUndeliveredByUserId(userId, limit)
                .stream().map(this::toDto).toList();
    }

    private NotificationItemDto toDto(NotificationItemProjection p) {
        return new NotificationItemDto(
                p.getNotificationId(),
                p.getContent(),
                NotificationType.valueOf(p.getType()),
                p.getIsRead() != null && p.getIsRead() != 0,
                p.getCreatedAt()
        );
    }
}
