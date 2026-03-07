package com.example.onlyone.domain.notification.repository;

import java.util.List;

public interface NotificationRepositoryCustom {

    int markAsReadByIdAndUserId(Long notificationId, Long userId);

    /** @return true if the deleted notification was unread */
    boolean deleteByIdAndUserId(Long notificationId, Long userId);

    /**
     * 워터마크 방식 전체 읽음.
     * notification 테이블 대량 UPDATE 없이 user_notification_state에 upsert.
     *
     * @return 1 if watermark updated, 0 if no notifications exist
     */
    long markAllAsReadByUserId(Long userId);

    void markDeliveredByIds(List<Long> notificationIds);
}
