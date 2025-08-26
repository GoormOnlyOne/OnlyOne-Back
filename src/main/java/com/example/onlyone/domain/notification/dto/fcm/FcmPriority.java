package com.example.onlyone.domain.notification.dto.fcm;

/**
 * FCM 알림 우선순위
 */
public enum FcmPriority {
    HIGH(1),
    NORMAL(2),
    LOW(3);
    
    private final int value;
    
    FcmPriority(int value) {
        this.value = value;
    }
    
    public int getValue() { 
        return value; 
    }
}