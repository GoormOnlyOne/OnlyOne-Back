package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 알림 타입 캐시 전용 서비스
 * @Cacheable self-invocation 문제를 해결하기 위한 별도 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTypeCacheService {
    
    private final NotificationTypeRepository notificationTypeRepository;
    
    /**
     * 알림 타입 조회 (로컬 캐시 적용 - 마스터 데이터)
     * 4개 타입만 존재하고 거의 불변이므로 로컬 캐시 최적
     */
    @Cacheable(value = "notificationTypes", key = "#type")
    public NotificationType findByType(Type type) {
        log.debug("NotificationType DB 조회: type={}", type);
        return notificationTypeRepository.findByType(type)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));
    }
}