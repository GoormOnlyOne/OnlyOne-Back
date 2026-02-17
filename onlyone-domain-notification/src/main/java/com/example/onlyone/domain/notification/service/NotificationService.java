package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationActionDto;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
// TODO: SseEmittersService를 infra 모듈의 SseEventSender로 대체 필요
// import com.example.onlyone.sse.service.SseEmittersService;
import com.example.onlyone.sse.service.SseEventSender;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
// Redis 캐시 제거 (부하 테스트에서 역효과 확인 - 높은 유저 카디널리티 + 낮은 히트율)
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 알림 서비스 (배치 처리 기반)
 *
 * SSE를 통한 실시간 알림 전송을 배치로 처리하여 성능을 최적화합니다.
 * - 트랜잭션 커밋 후 큐에 알림을 추가
 * - 50ms 주기로 스케줄러가 배치 SSE 전송
 * - Spring Security를 통한 자동 사용자 인증
 */
@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEventSender sseEventSender;

    // ========== 배치 처리 설정 (application.yml에서 조정 가능) ==========

    @Value("${app.notification.batch-size:10}")
    private int batchSize;

    @Value("${app.notification.max-queue-size-per-user:100}")
    private int maxQueueSizePerUser;

    @Value("${app.notification.batch-timeout-seconds:5}")
    private int batchTimeoutSeconds;

    private final Map<Long, BlockingQueue<Notification>> userNotificationQueues = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;
    private volatile CompletableFuture<Void> currentBatchFuture = CompletableFuture.completedFuture(null);

    public NotificationService(NotificationRepository notificationRepository,
                               ApplicationEventPublisher eventPublisher,
                               SseEventSender sseEventSender) {
        this.notificationRepository = notificationRepository;
        this.eventPublisher = eventPublisher;
        this.sseEventSender = sseEventSender;
    }

    // ========== PUBLIC API - 컨트롤러용 (DTO 기반) ==========

    /**
     * 알림 목록 조회
     */
    @Transactional(readOnly = true)
    public NotificationListResponseDto getNotifications(NotificationQueryDto dto) {
        Long userId = getCurrentUserId();
        int size = Math.min(dto.size(), 30);

        List<NotificationItemDto> notifications =
                notificationRepository.findNotificationsByUserId(userId, dto.cursor(), size + 1);

        log.debug("알림 조회: userId={}, count={}", userId, notifications.size());
        return buildNotificationListResponse(notifications, size, userId);
    }

    /**
     * 읽지 않은 알림 개수 조회
     */
    @Transactional(readOnly = true)
    public Long getUnreadCount() {
        Long userId = getCurrentUserId();
        return notificationRepository.countUnreadByUserId(userId);
    }

    /**
     * 알림 읽음 처리
     */
    @Transactional
    public void markAsRead(NotificationActionDto dto) {
        Long userId = getCurrentUserId();
        Notification notification = findNotification(dto.notificationId());
        validateNotificationOwnership(notification, userId);
        notification.markAsRead();
        log.debug("알림 읽음: userId={}, notificationId={}", userId, dto.notificationId());
    }

    /**
     * 모든 알림 읽음 처리
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAllAsRead() {
        Long userId = getCurrentUserId();
        long markedCount = notificationRepository.markAllAsReadByUserId(userId);
        if (markedCount > 0) {
            log.info("모든 알림 읽음: userId={}, count={}", userId, markedCount);
        }
    }

    /**
     * 알림 삭제
     */
    @Transactional
    public void deleteNotification(NotificationActionDto dto) {
        Long userId = getCurrentUserId();
        Notification notification = findNotification(dto.notificationId());
        validateNotificationOwnership(notification, userId);
        notificationRepository.delete(notification);
        log.debug("알림 삭제: userId={}, notificationId={}", userId, dto.notificationId());
    }

    /**
     * 배치 처리 상태 조회 (모니터링)
     */
    public Map<String, Object> getBatchStatus() {
        int totalQueueSize = userNotificationQueues.values().stream()
                .mapToInt(BlockingQueue::size)
                .sum();

        return Map.of(
                "activeUsers", userNotificationQueues.size(),
                "totalQueuedNotifications", totalQueueSize,
                "averageQueueSize", userNotificationQueues.isEmpty() ? 0 :
                        totalQueueSize / (double) userNotificationQueues.size(),
                "batchSize", batchSize,
                "maxQueueSizePerUser", maxQueueSizePerUser
        );
    }

    // ========== PUBLIC API - 다른 도메인 서비스용 ==========

    /**
     * 알림 생성 (DTO 기반)
     */
    @Transactional
    public void createNotification(NotificationCreateDto dto) {
        Notification notification = Notification.create(dto.user(), dto.type(), dto.args());
        notificationRepository.save(notification);
        publishNotificationCreatedEvent(notification);
        log.debug("알림 생성: userId={}, type={}, id={}",
                dto.user().getUserId(), dto.type(), notification.getId());
    }

    /**
     * 알림 생성 (편의 메서드)
     */
    @Transactional
    public void createNotification(User user, NotificationType type, String... args) {
        createNotification(new NotificationCreateDto(user, type, args));
    }

    // ========== 배치 처리 (이벤트 리스너 & 스케줄러) ==========

    /**
     * 알림 생성 이벤트 수신 → 배치 큐에 추가
     * 트랜잭션 커밋 후 실행되어 이중 처리 방지
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        if (shuttingDown) {
            return;
        }

        log.debug("[Event.Received] type=NotificationCreatedEvent, notificationId={}", event.notification().getId());
        Notification notification = event.notification();
        Long userId = notification.getUser().getUserId();

        // 온라인 사용자만 큐에 추가
        if (!sseEventSender.isUserConnected(userId)) {
            log.debug("오프라인 사용자 배치 스킵: userId={}", userId);
            return;
        }

        BlockingQueue<Notification> userQueue = userNotificationQueues.computeIfAbsent(
                userId, k -> new LinkedBlockingQueue<>(maxQueueSizePerUser)
        );

        if (!userQueue.offer(notification)) {
            log.warn("큐 포화: userId={}, size={}", userId, userQueue.size());
            userQueue.poll();
            userQueue.offer(notification);
        }

        log.debug("배치 큐 추가: userId={}, queueSize={}", userId, userQueue.size());
    }

    /**
     * 배치 처리 주기 실행
     * 이전 배치가 완료된 후에만 다음 배치 실행 (백프레셔)
     */
    @Scheduled(fixedDelayString = "${app.notification.batch-processing-interval:100}")
    public void processBatchNotifications() {
        if (shuttingDown || userNotificationQueues.isEmpty()) {
            return;
        }

        // 이전 배치가 아직 진행 중이면 스킵 (백프레셔)
        if (!currentBatchFuture.isDone()) {
            log.debug("이전 배치 진행 중, 스킵");
            return;
        }

        int totalProcessed = 0;
        List<CompletableFuture<Void>> userFutures = new ArrayList<>();
        List<Map.Entry<Long, BlockingQueue<Notification>>> entries =
                new ArrayList<>(userNotificationQueues.entrySet());

        for (Map.Entry<Long, BlockingQueue<Notification>> entry : entries) {
            Long userId = entry.getKey();
            BlockingQueue<Notification> queue = entry.getValue();

            if (!sseEventSender.isUserConnected(userId)) {
                userNotificationQueues.remove(userId);
                log.debug("연결 해제 큐 정리: userId={}", userId);
                continue;
            }

            List<Notification> batch = new ArrayList<>();
            for (int i = 0; i < batchSize && !queue.isEmpty(); i++) {
                Notification notification = queue.poll();
                if (notification != null) {
                    batch.add(notification);
                }
            }

            if (!batch.isEmpty()) {
                userFutures.add(processBatchForUser(userId, batch));
                totalProcessed += batch.size();
            }

            if (queue.isEmpty()) {
                userNotificationQueues.remove(userId);
            }
        }

        if (!userFutures.isEmpty()) {
            // 모든 사용자 배치를 추적하여 백프레셔 적용
            currentBatchFuture = CompletableFuture.allOf(userFutures.toArray(new CompletableFuture[0]))
                    .orTimeout(batchTimeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("배치 타임아웃 또는 오류: {}", ex.getMessage());
                        return null;
                    });
            log.debug("배치 처리: total={}, activeUsers={}", totalProcessed, userNotificationQueues.size());
        }
    }

    /**
     * 사용자별 알림 배치 처리
     * SSE 전송을 병렬로 실행하고 완료를 실제로 대기
     *
     * 주의: private 메서드이므로 @Async/@Transactional 사용 불가 (Spring AOP 프록시 미적용)
     * CompletableFuture를 통해 비동기 처리
     */
    private CompletableFuture<Void> processBatchForUser(Long userId, List<Notification> notifications) {
        try {
            List<CompletableFuture<Boolean>> futures = notifications.stream()
                    .map(notification ->
                            sseEventSender.sendEvent(userId, "notification", notification)
                                    .exceptionally(ex -> {
                                        log.debug("SSE 전송 실패: notificationId={}", notification.getId());
                                        return false;
                                    })
                                    .thenApply(success -> {
                                        if (success) {
                                            notification.markSseSent();
                                        }
                                        return success;
                                    })
                    )
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("배치 완료: userId={}, count={}", userId, notifications.size());
                        }
                    });

        } catch (Exception e) {
            log.warn("배치 처리 실패: userId={}, count={}", userId, notifications.size(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("NotificationService 종료 시작");
        shuttingDown = true;

        // 진행 중인 배치 완료 대기
        try {
            if (!currentBatchFuture.isDone()) {
                log.info("진행 중인 배치 완료 대기...");
                currentBatchFuture.get(batchTimeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("배치 완료 대기 중 오류: {}", e.getMessage());
        }

        int remaining = userNotificationQueues.values().stream().mapToInt(BlockingQueue::size).sum();
        log.info("NotificationService 종료 완료: 미처리 알림 {}개", remaining);
        userNotificationQueues.clear();
    }

    // ========== PRIVATE HELPERS ==========

    /**
     * 알림 생성 이벤트 발행 (온라인 사용자만)
     */
    private void publishNotificationCreatedEvent(Notification notification) {
        try {
            Long userId = notification.getUser().getUserId();
            if (sseEventSender.isUserConnected(userId)) {
                NotificationCreatedEvent event = new NotificationCreatedEvent(notification);
                eventPublisher.publishEvent(event);
                log.debug("이벤트 발행: notificationId={}, userId={}", notification.getId(), userId);
            }
        } catch (Exception e) {
            log.warn("이벤트 발행 실패: notificationId={}", notification.getId(), e);
        }
    }

    /**
     * 현재 사용자 ID 조회 (Spring Security)
     */
    public Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof UserPrincipal)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        return ((UserPrincipal) principal).getUserId();
    }

    /**
     * 알림 조회 (fetch join)
     */
    private Notification findNotification(Long notificationId) {
        Notification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
        if (notification == null) {
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return notification;
    }

    /**
     * 소유권 검증
     */
    private void validateNotificationOwnership(Notification notification, Long userId) {
        if (!notification.getUser().getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /**
     * 알림 목록 응답 생성
     */
    private NotificationListResponseDto buildNotificationListResponse(
            List<NotificationItemDto> notifications, int requestedSize, Long userId) {

        boolean hasMore = notifications.size() > requestedSize;
        List<NotificationItemDto> actualNotifications = hasMore ?
                notifications.subList(0, requestedSize) : notifications;

        Long nextCursor = actualNotifications.isEmpty() ? null :
                actualNotifications.get(actualNotifications.size() - 1).notificationId();

        // unreadCount는 별도 /unread-count 엔드포인트로 분리 (목록 조회 시 이중 쿼리 제거)
        return new NotificationListResponseDto(
                actualNotifications,
                nextCursor,
                hasMore,
                null
        );
    }
}