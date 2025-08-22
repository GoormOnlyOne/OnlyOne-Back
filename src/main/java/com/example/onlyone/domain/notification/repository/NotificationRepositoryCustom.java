package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.Type;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepositoryCustom {
    
    /**
     * 사용자별 알림 목록 조회 (커서 기반 페이지네이션)
     */
    List<NotificationItemDto> findNotificationsByUserId(
            Long userId, 
            Long cursor, 
            int size
    );
    
    /**
     * 특정 타입의 알림 목록 조회
     */
    List<NotificationItemDto> findNotificationsByUserIdAndType(
            Long userId, 
            Type type,
            Long cursor,
            int size
    );
    
    /**
     * 읽지 않은 알림 개수 조회
     */
    Long countUnreadByUserId(Long userId);
    
    /**
     * 읽지 않은 알림 목록 조회 (전체)
     */
    List<AppNotification> findUnreadNotificationsByUserId(Long userId);
    
    /**
     * 전송 실패한 FCM 알림 목록 조회
     */
    List<AppNotification> findFailedFcmNotificationsByUserId(Long userId);
    
    /**
     * 특정 시간 이후의 알림 목록 조회 (SSE 재연결 지원)
     */
    List<AppNotification> findNotificationsByUserIdAfter(
            Long userId, 
            LocalDateTime after
    );
    
    /**
     * ID로 단일 알림 조회 (fetchJoin 포함)
     */
    AppNotification findByIdWithFetchJoin(Long notificationId);
    
    /**
     * 알림 통계 정보 조회
     */
    NotificationStats getNotificationStats(Long userId);
    
    /**
     * 모든 알림을 읽음 처리
     */
    long markAllAsReadByUserId(Long userId);
    
    /**
     * 오래된 알림 삭제 (읽음 처리된 알림만)
     */
    long deleteOldNotifications(int daysToKeep);
    
    /**
     * 배치 삽입 (성능 최적화)
     */
    void batchInsertNotifications(List<AppNotification> notifications);
    
    /**
     * 알림 통계 DTO
     */
    record NotificationStats(
            Long totalCount,
            Long unreadCount,
            Long fcmSentCount,
            Long fcmFailedCount
    ) {}
}