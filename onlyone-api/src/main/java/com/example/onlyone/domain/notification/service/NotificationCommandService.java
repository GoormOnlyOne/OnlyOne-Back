package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.port.NotificationEventPublisher;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import com.example.onlyone.domain.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationCommandService {

    private final NotificationStoragePort storagePort;
    private final NotificationEventPublisher eventPublisher;
    private final AuthService authService;
    private final NotificationUnreadCounter unreadCounter;

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "notificationList", allEntries = true),
            @CacheEvict(value = "unreadCount", allEntries = true)
    })
    public void markAsRead(Long notificationId) {
        Long userId = authService.getCurrentUserId();
        int updated = storagePort.markAsReadByIdAndUserId(notificationId, userId);
        if (updated > 0) {
            unreadCounter.decrement(userId);
        }
        log.debug("알림 읽음: userId={}, notificationId={}, updated={}", userId, notificationId, updated);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "notificationList", allEntries = true),
            @CacheEvict(value = "unreadCount", allEntries = true)
    })
    public void deleteNotification(Long notificationId) {
        Long userId = authService.getCurrentUserId();
        boolean wasUnread = storagePort.deleteByIdAndUserId(notificationId, userId);
        if (wasUnread) {
            unreadCounter.decrement(userId);
        }
        log.debug("알림 삭제: userId={}, notificationId={}", userId, notificationId);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "notificationList", allEntries = true),
            @CacheEvict(value = "unreadCount", allEntries = true)
    })
    public void markAllAsRead() {
        Long userId = authService.getCurrentUserId();
        long changed = storagePort.markAllAsReadByUserId(userId);
        unreadCounter.reset(userId);
        log.debug("전체 읽음: userId={}, changed={}", userId, changed);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "notificationList", allEntries = true),
            @CacheEvict(value = "unreadCount", allEntries = true)
    })
    public void createNotification(NotificationCreateDto dto) {
        Notification notification = Notification.create(dto.user(), dto.type(), dto.name());
        String content = notification.getContent();

        Long notificationId = storagePort.save(dto.user().getUserId(), dto.type(), content);
        unreadCounter.increment(dto.user().getUserId());

        eventPublisher.publish(new NotificationCreatedEvent(
                notificationId,
                dto.user().getUserId(),
                content,
                dto.type(),
                false,
                LocalDateTime.now()
        ));
        log.debug("알림 생성: userId={}, type={}, id={}",
                dto.user().getUserId(), dto.type(), notificationId);
    }
}
