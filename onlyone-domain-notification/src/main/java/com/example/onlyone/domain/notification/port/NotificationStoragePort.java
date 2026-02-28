package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.NotificationType;

import java.util.List;

/**
 * 알림 저장소 추상화 포트.
 * MySQL, MongoDB 등 구현체를 {@code app.notification.storage} 프로퍼티로 교체할 수 있다.
 */
public interface NotificationStoragePort {

    Long save(Long userId, NotificationType type, String content);

    List<NotificationItemDto> findByUserId(Long userId, Long cursor, int size);

    Long countUnreadByUserId(Long userId);

    int markAsReadByIdAndUserId(Long notificationId, Long userId);

    boolean deleteByIdAndUserId(Long notificationId, Long userId);

    long markAllAsReadByUserId(Long userId);

    void markDeliveredByIds(List<Long> notificationIds);

    List<NotificationItemDto> findUndeliveredByUserId(Long userId, int limit);
}
