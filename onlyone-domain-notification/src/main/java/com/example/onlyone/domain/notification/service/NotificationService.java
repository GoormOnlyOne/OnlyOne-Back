package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 30;
    private static final String UNREAD_COUNT_KEY_PREFIX = "notification:unread:";

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthService authService;
    private final StringRedisTemplate stringRedisTemplate;

    // ========== 조회 ==========

    /** 커서 기반 페이징으로 알림 목록 조회 */
    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        Long userId = getCurrentUserId();
        int size = Math.min(dto.size(), MAX_PAGE_SIZE);

        // size + 1개를 조회하여 다음 페이지 존재 여부 판별
        List<NotificationItemDto> notifications =
                notificationRepository.findNotificationsByUserId(userId, dto.cursor(), size + 1);

        log.debug("알림 조회: userId={}, count={}", userId, notifications.size());
        return buildPagedResponse(notifications, size);
    }

    /** 읽지 않은 알림 개수 조회 — Redis 카운터 우선, fallback DB */
    public Long getUnreadCount() {
        Long userId = getCurrentUserId();
        String key = UNREAD_COUNT_KEY_PREFIX + userId;

        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Math.max(0L, Long.parseLong(cached));
            }
        } catch (Exception e) {
            log.warn("Redis 읽기 실패, DB fallback: userId={}", userId, e);
        }

        // Redis 미스 → DB 조회 후 TTL 10분으로 캐시
        Long count = notificationRepository.countUnreadByUserId(userId);
        try {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(count), Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("Redis 캐시 저장 실패: userId={}", userId, e);
        }
        return count;
    }

    // ========== 상태 변경 ==========

    /** 단건 읽음 처리 — 네이티브 쿼리로 단일 UPDATE, 엔티티 로딩 없이 처리 */
    @Transactional
    public void markAsRead(Long notificationId) {
        Long userId = getCurrentUserId();
        // 소유권 검증 + 읽음 처리를 단일 쿼리로 수행
        int updated = notificationRepository.markAsReadByIdAndUserId(notificationId, userId);
        if (updated > 0) {
            decrementUnreadCount(userId);
        }
        log.debug("알림 읽음: userId={}, notificationId={}, updated={}", userId, notificationId, updated);
    }

    /** 전체 읽음 처리 (벌크 업데이트) */
    @Transactional
    public void markAllAsRead() {
        Long userId = getCurrentUserId();
        long markedCount = notificationRepository.markAllAsReadByUserId(userId);
        if (markedCount > 0) {
            // 전체 읽음 → 카운터를 0으로 리셋
            try {
                stringRedisTemplate.opsForValue().set(
                        UNREAD_COUNT_KEY_PREFIX + userId, "0", Duration.ofMinutes(10));
            } catch (Exception e) {
                log.warn("Redis 카운터 리셋 실패: userId={}", userId, e);
            }
            log.debug("모든 알림 읽음: userId={}, count={}", userId, markedCount);
        }
    }

    /** 단건 삭제 — 소유권 검증 + 삭제 + 읽음 상태 확인을 최소 쿼리로 처리 */
    @Transactional
    public void deleteNotification(Long notificationId) {
        Long userId = getCurrentUserId();
        // 삭제 전 읽음 여부 확인 (소유권 검증 포함, 단일 네이티브 쿼리)
        boolean wasUnread = notificationRepository.deleteByIdAndUserId(notificationId, userId);

        if (wasUnread) {
            decrementUnreadCount(userId);
        }
        log.debug("알림 삭제: userId={}, notificationId={}", userId, notificationId);
    }

    // ========== 다른 도메인 서비스용 ==========

    /** 알림 생성 후 SSE 배치 전송을 위한 이벤트 발행 */
    @Transactional
    public void createNotification(NotificationCreateDto dto) {
        Notification notification = Notification.create(dto.user(), dto.type(), dto.args());
        notificationRepository.save(notification);

        // 미읽음 카운터 증가
        incrementUnreadCount(dto.user().getUserId());

        eventPublisher.publishEvent(new NotificationCreatedEvent(notification));
        log.debug("알림 생성: userId={}, type={}, id={}",
                dto.user().getUserId(), dto.type(), notification.getId());
    }

    // ========== private ==========

    private Long getCurrentUserId() {
        return authService.getCurrentUserId();
    }

    private void incrementUnreadCount(Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(UNREAD_COUNT_KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Redis 카운터 증가 실패: userId={}", userId, e);
        }
    }

    private void decrementUnreadCount(Long userId) {
        try {
            String key = UNREAD_COUNT_KEY_PREFIX + userId;
            Long result = stringRedisTemplate.opsForValue().decrement(key);
            // 음수 방지
            if (result != null && result < 0) {
                stringRedisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("Redis 카운터 감소 실패: userId={}", userId, e);
        }
    }

    /** size + 1 패턴으로 커서 기반 페이징 응답 생성 */
    private NotificationListResponseDto buildPagedResponse(
            List<NotificationItemDto> notifications, int requestedSize) {

        boolean hasMore = notifications.size() > requestedSize;
        List<NotificationItemDto> page = hasMore
                ? notifications.subList(0, requestedSize)
                : notifications;

        Long nextCursor = page.isEmpty()
                ? null
                : page.get(page.size() - 1).notificationId();

        return new NotificationListResponseDto(page, nextCursor, hasMore);
    }
}
