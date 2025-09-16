package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Notification;
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
     * 읽지 않은 알림 개수 조회
     */
    Long countUnreadByUserId(Long userId);
    
    /**
     * 읽지 않은 알림 목록 조회 (전체)
     */
    List<Notification> findUnreadNotificationsByUserId(Long userId);
    
    /**
     * ID로 단일 알림 조회 (fetchJoin 포함)
     */
    Notification findByIdWithFetchJoin(Long notificationId);
    
    /**
     * 모든 알림을 읽음 처리
     */
    long markAllAsReadByUserId(Long userId);
    
    /**
     * SSE 전송 상태 업데이트
     */
    long updateSseSentStatus(Long notificationId, boolean sent);
}