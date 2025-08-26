package com.example.onlyone.domain.notification.model;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.dto.fcm.FcmPriority;
import lombok.Getter;

import java.util.Objects;

/**
 * FCM 알림 전송 작업 모델
 * 우선순위 큐에서 사용되는 도메인 모델
 */
@Getter
public class FcmNotificationTask implements Comparable<FcmNotificationTask> {
    private final AppNotification notification;
    private final FcmPriority priority;
    private final long timestamp;
    
    private FcmNotificationTask(AppNotification notification, FcmPriority priority) {
        this.notification = Objects.requireNonNull(notification);
        this.priority = Objects.requireNonNull(priority);
        this.timestamp = System.currentTimeMillis();
    }
    
    public static FcmNotificationTask of(AppNotification notification, FcmPriority priority) {
        return new FcmNotificationTask(notification, priority);
    }
    
    @Override
    public int compareTo(FcmNotificationTask other) {
        // 높은 우선순위가 먼저 처리되도록
        int priorityCompare = other.priority.getValue() - this.priority.getValue();
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        // 동일한 우선순위일 경우 먼저 들어온 것 먼저 (FIFO)
        return Long.compare(this.timestamp, other.timestamp);
    }
}