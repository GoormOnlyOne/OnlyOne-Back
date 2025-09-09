package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이벤트 중복 제거 및 연령 필터링 서비스
 * 중복 알림 방지 및 오래된 이벤트 필터링으로 성능 최적화
 */
@Service
@Slf4j
public class NotificationDeduplicationService {
    
    private final Map<String, EventInfo> recentEvents = new ConcurrentHashMap<>();
    
    @Value("${notification.deduplication.window-seconds:5}")
    private int deduplicationWindowSeconds;
    
    @Value("${notification.max-age-minutes:10}")
    private int maxEventAgeMinutes;
    
    /**
     * 알림 전송 필요 여부 판단
     */
    public boolean shouldSendNotification(Long userId, Type type, String entityId, LocalDateTime eventTime) {
        // 1. 이벤트 연령 검사
        if (isEventTooOld(eventTime)) {
            log.debug("Event too old, discarding: userId={}, type={}, age={}min", 
                    userId, type, Duration.between(eventTime, LocalDateTime.now()).toMinutes());
            return false;
        }
        
        // 2. 중복 제거
        String deduplicationKey = createDeduplicationKey(userId, type, entityId);
        EventInfo lastEvent = recentEvents.get(deduplicationKey);
        
        LocalDateTime now = LocalDateTime.now();
        Duration windowDuration = Duration.ofSeconds(deduplicationWindowSeconds);
        
        if (lastEvent != null && Duration.between(lastEvent.timestamp, now).compareTo(windowDuration) < 0) {
            // 중복 이벤트 - 기존 이벤트 업데이트
            lastEvent.timestamp = now;
            lastEvent.count++;
            
            log.debug("Duplicate event within {}s window, merging: userId={}, type={}, count={}", 
                    deduplicationWindowSeconds, userId, type, lastEvent.count);
            return false;
        }
        
        // 새로운 이벤트 등록
        recentEvents.put(deduplicationKey, new EventInfo(now, 1));
        return true;
    }
    
    /**
     * 중복된 알림의 통합된 메시지 생성
     */
    public String createMergedNotificationMessage(Long userId, Type type, String entityId, String originalMessage) {
        String key = createDeduplicationKey(userId, type, entityId);
        EventInfo eventInfo = recentEvents.get(key);
        
        if (eventInfo != null && eventInfo.count > 1) {
            return originalMessage + String.format(" (외 %d건)", eventInfo.count - 1);
        }
        
        return originalMessage;
    }
    
    private boolean isEventTooOld(LocalDateTime eventTime) {
        Duration maxAge = Duration.ofMinutes(maxEventAgeMinutes);
        Duration eventAge = Duration.between(eventTime, LocalDateTime.now());
        return eventAge.compareTo(maxAge) > 0;
    }
    
    private String createDeduplicationKey(Long userId, Type type, String entityId) {
        return String.format("%d:%s:%s", userId, type, entityId != null ? entityId : "null");
    }
    
    /**
     * 주기적 정리 작업
     */
    @Scheduled(fixedRate = 30000) // 30초마다
    public void cleanupExpiredEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(deduplicationWindowSeconds * 2);
        
        int removed = 0;
        var iterator = recentEvents.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().timestamp.isBefore(cutoff)) {
                iterator.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            log.debug("Cleaned up {} expired deduplication entries", removed);
        }
    }
    
    /**
     * 현재 중복 제거 상태 조회
     */
    public int getActiveDeduplicationEntries() {
        return recentEvents.size();
    }
    
    private static class EventInfo {
        private LocalDateTime timestamp;
        private int count;
        
        public EventInfo(LocalDateTime timestamp, int count) {
            this.timestamp = timestamp;
            this.count = count;
        }
    }
}