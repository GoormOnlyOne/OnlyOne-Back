package com.example.onlyone.domain.notification.dto.response;

import java.time.LocalDateTime;

/**
 * 알림 목록 네이티브 쿼리 인터페이스 프로젝션.
 * 컬럼 alias와 getter 이름이 1:1 매핑된다.
 */
public interface NotificationItemProjection {
    Long getNotificationId();
    String getContent();
    String getType();
    Long getIsRead();
    LocalDateTime getCreatedAt();
}
