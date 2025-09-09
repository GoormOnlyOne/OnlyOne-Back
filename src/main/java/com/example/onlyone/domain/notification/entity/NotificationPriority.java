package com.example.onlyone.domain.notification.entity;

/**
 * 알림 우선순위
 */
public enum NotificationPriority {
    CRITICAL(1),    // 시스템 중요 알림, 보안
    HIGH(2),        // 사용자 액션 관련 (댓글, 좋아요)
    NORMAL(3),      // 일반 알림
    LOW(4);         // 마케팅, 추천
    
    private final int level;
    
    NotificationPriority(int level) {
        this.level = level;
    }
    
    public int getLevel() {
        return level;
    }
}