package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.*;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 알림 서비스
 *
 * 알림 시스템의 핵심 비즈니스 로직을 담당하는 서비스 계층입니다.
 * SSE(Server-Sent Events)를 통한 실시간 알림 전송과
 * 데이터베이스 기반의 영구 알림 저장을 통합 관리합니다.
 *
 * 주요 책임:
 * 1. 알림 생성 및 저장
 * 2. SSE를 통한 실시간 알림 전송
 * 3. 커서 기반 페이징을 통한 알림 목록 조회
 * 4. 알림 읽음 상태 관리
 * 5. SSE 연결 생명주기 관리
 *
 * 아키텍처 특징:
 * - 이벤트 기반 아키텍처 (ApplicationEventPublisher 활용)
 * - 메모리 기반 SSE 연결 관리 (ConcurrentHashMap)
 * - 트랜잭션 분리 (읽기 전용 트랜잭션 최적화)
 * - 프로젝션 패턴을 통한 성능 최적화
 *
 * 확장성 고려사항:
 * - 다중 서버 환경에서는 Redis 기반 SSE 관리 필요
 * - FCM, 웹 푸시 등 다중 채널 알림 지원
 * - 알림 템플릿 다국어 지원
 * - 알림 스케줄링 및 배치 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final UserRepository userRepository;
    private final NotificationTypeRepository notificationTypeRepository;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * SSE 연결 관리 맵
     *
     * 메모리 기반으로 사용자별 SSE 연결을 관리합니다.
     * Key: 사용자 ID, Value: SSE Emitter 객체
     *
     * ConcurrentHashMap 사용 이유:
     * - 멀티스레드 환경에서 안전한 동시 접근
     * - 높은 성능의 읽기/쓰기 작업
     *
     * 제한사항:
     * - 단일 서버 환경에서만 동작
     * - 서버 재시작 시 모든 연결 끊어짐
     *
     * 다중 서버 환경 대안:
     * - Redis를 활용한 분산 SSE 관리
     * - WebSocket + Message Broker (RabbitMQ, Kafka)
     */
    private final Map<Long, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    /**
     * SSE 연결 생성 및 관리
     *
     * 클라이언트와의 SSE 연결을 생성하고 생명주기를 관리합니다.
     * 연결 생성 즉시 하트비트를 전송하여 연결 상태를 확인합니다.
     *
     * @param userId 연결을 요청한 사용자 ID
     * @return 생성된 SSE Emitter 객체
     *
     * 연결 관리 전략:
     * - 무제한 타임아웃 (0L) 설정으로 장기간 연결 유지
     * - 연결 종료/오류 시 자동 정리를 통한 메모리 누수 방지
     * - 하트비트를 통한 연결 상태 확인
     *
     * 모니터링 포인트:
     * - 동시 연결 수 모니터링
     * - 연결 해제 빈도 및 원인 분석
     * - 하트비트 실패율 추적
     */
    public SseEmitter createSseConnection(Long userId) {
        log.info("SSE 연결 생성 시작 - 사용자 ID: {}", userId);

        // 기존 연결이 있다면 정리 (중복 연결 방지)
        SseEmitter existingEmitter = sseEmitters.get(userId);
        if (existingEmitter != null) {
            log.info("기존 SSE 연결 발견, 정리 진행 - 사용자 ID: {}", userId);
            existingEmitter.complete();
            sseEmitters.remove(userId);
        }

        SseEmitter emitter = new SseEmitter(0L); // 무제한 타임아웃
        sseEmitters.put(userId, emitter);

        log.info("SSE 연결 생성 완료 - 사용자 ID: {}, 현재 연결 수: {}", userId, sseEmitters.size());

        // 연결 완료 시 정리 콜백 등록
        emitter.onCompletion(() -> {
            log.info("SSE 연결 정상 종료 - 사용자 ID: {}", userId);
            sseEmitters.remove(userId);
            log.debug("연결 정리 완료 - 남은 연결 수: {}", sseEmitters. size());
        });

        // 연결 타임아웃 시 정리 콜백 등록
        emitter.onTimeout(() -> {
            log.warn("SSE 연결 타임아웃 - 사용자 ID: {}", userId);
            sseEmitters.remove(userId);
            log.debug("타임아웃 연결 정리 완료 - 남은 연결 수: {}", sseEmitters.size());
        });

        // 연결 오류 시 정리 콜백 등록
        emitter.onError((ex) -> {
            log.error("SSE 연결 오류 발생 - 사용자 ID: {}, 오류: {}", userId, ex.getMessage(), ex);
            sseEmitters.remove(userId);
            log.debug("오류 연결 정리 완료 - 남은 연결 수: {}", sseEmitters.size());
        });

        // 연결 확인용 하트비트 전송
        try {
            emitter.send(SseEmitter.event()
                .name("heartbeat")
                .data("connected"));
            log.debug("초기 하트비트 전송 성공 - 사용자 ID: {}", userId);
        } catch (IOException e) {
            log.error("초기 하트비트 전송 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage(), e);
            sseEmitters.remove(userId);
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        }

        return emitter;
    }

    /**
     * 알림 생성 및 전송
     *
     * 새로운 알림을 생성하고 데이터베이스에 저장한 후,
     * SSE를 통해 실시간으로 사용자에게 전송합니다.
     *
     * @param requestDto 알림 생성 요청 정보
     * @return 생성된 알림 정보
     *
     * 처리 흐름:
     * 1. 사용자 및 알림 타입 검증
     * 2. 템플릿 기반 알림 메시지 생성
     * 3. 데이터베이스 저장 (트랜잭션)
     * 4. SSE를 통한 실시간 전송
     * 5. FCM 전송 (향후 구현)
     *
     * 오류 처리:
     * - 사용자 미존재: USER_NOT_FOUND
     * - 알림 타입 미존재: NOTIFICATION_TYPE_NOT_FOUND
     * - SSE 전송 실패: 로그 기록 (알림 저장은 유지)
     *
     * 성능 고려사항:
     * - 알림 생성과 SSE 전송의 독립성 보장
     * - 대량 알림 생성 시 배치 처리 고려
     */
    @Transactional
    public NotificationCreateResponseDto createNotification(NotificationCreateRequestDto requestDto) {
        log.info("알림 생성 시작 - 사용자 ID: {}, 타입: {}", requestDto.getUserId(), requestDto.getType());

        // 사용자 조회 및 검증
        User user = userRepository.findById(requestDto.getUserId())
            .orElseThrow(() -> {
                log.error("사용자 조회 실패 - 존재하지 않는 사용자 ID: {}", requestDto.getUserId());
                return new CustomException(ErrorCode.USER_NOT_FOUND);
            });

        log.debug("사용자 조회 성공 - 사용자명: {}", user.getUserId());

        // 알림 타입 조회 및 검증
        NotificationType notificationType = notificationTypeRepository.findByType(requestDto.getType())
            .orElseThrow(() -> {
                log.error("알림 타입 조회 실패 - 존재하지 않는 타입: {}", requestDto.getType());
                return new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
            });

        log.debug("알림 타입 조회 성공 - 타입: {}, 템플릿: {}", notificationType.getType(), notificationType.getTemplate());

        // 알림 생성 (팩토리 메서드 사용)
        Notification notification = Notification.create(
            user,
            notificationType,
            requestDto.getArgs()
        );

        log.debug("알림 객체 생성 완료 - 메시지: {}", notification.getContent());

        // 데이터베이스 저장
        Notification savedNotification = notificationRepository.save(notification);
        log.info("알림 저장 완료 - 알림 ID: {}, 사용자 ID: {}", savedNotification.getNotificationId(), savedNotification.getUser().getUserId());

        // 실시간 SSE 전송 (비동기적으로 처리, 실패해도 알림 생성은 유지)
        try {
            sendSseNotification(requestDto.getUserId(), savedNotification);
            log.debug("SSE 알림 전송 완료 - 알림 ID: {}", savedNotification.getNotificationId());
        } catch (Exception e) {
            log.error("SSE 알림 전송 실패 - 알림 ID: {}, 오류: {}", savedNotification.getNotificationId(), e.getMessage(), e);
            // SSE 전송 실패해도 알림 생성은 성공으로 처리
        }

        // FCM 전송 (향후 구현)
        // TODO: FCM 전송 로직 구현
        // try {
        //     sendFcmNotification(savedNotification);
        //     savedNotification.markFcmSent(true);
        //     log.debug("FCM 알림 전송 완료 - 알림 ID: {}", savedNotification.getNotificationId());
        // } catch (Exception e) {
        //     log.error("FCM 알림 전송 실패 - 알림 ID: {}, 오류: {}", savedNotification.getNotificationId(), e.getMessage(), e);
        //     savedNotification.markFcmSent(false);
        // }

        return NotificationCreateResponseDto.from(savedNotification);
    }

    /**
     * 알림 목록 조회 (커서 기반 페이징)
     *
     * 사용자의 알림 목록을 커서 기반 페이징으로 조회합니다.
     * 성능 최적화를 위해 프로젝션을 사용하고,
     * 읽지 않은 알림 개수도 함께 반환합니다.
     *
     * @param userId 사용자 ID
     * @param cursor 페이징 커서 (이전 페이지의 마지막 알림 ID)
     * @param size 페이지 크기
     * @return 알림 목록과 페이징 정보
     *
     * 페이징 전략:
     * - 커서 기반 페이징으로 일관된 성능 보장
     * - 최신 알림부터 내림차순 정렬
     * - hasMore 플래그로 추가 데이터 존재 여부 확인
     *
     */
    @Transactional(readOnly = true)
    public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
        log.info("알림 목록 조회 시작 - 사용자 ID: {}, 커서: {}, 크기: {}", userId, cursor, size);

        // 페이지 크기 제한 (DoS 공격 방지)
        if (size > 100) {
            size = 100;
            log.warn("페이지 크기 제한 적용 - 요청: {}, 적용: 100", size);
        }

        Pageable pageable = PageRequest.of(0, size);
        List<NotificationItemDto> notifications;

        // 첫 페이지 vs 커서 기반 페이지 구분
        if (cursor == null) {
            log.debug("첫 페이지 조회 실행");
            // 첫 페이지 조회
            List<NotificationListItem> items = notificationRepository.findTopByUser(userId, pageable);
            notifications = items.stream()
                .map(this::convertToItemDto)
                .collect(Collectors.toList());
            log.debug("첫 페이지 조회 완료 - 조회된 알림 수: {}", notifications.size());
        } else {
            log.debug("커서 기반 페이지 조회 실행 - 커서: {}", cursor);
            // 커서 이후 페이지 조회
            List<NotificationListItem> items = notificationRepository.findAfterCursor(userId, cursor, pageable);
            notifications = items.stream()
                .map(this::convertToItemDto)
                .collect(Collectors.toList());
            log.debug("커서 기반 페이지 조회 완료 - 조회된 알림 수: {}", notifications.size());
        }

        // 다음 커서 및 더보기 여부 계산
        Long nextCursor = null;
        boolean hasMore = false;

        if (!notifications.isEmpty()) {
            nextCursor = notifications.get(notifications.size() - 1).getNotificationId();
            log.debug("다음 커서 계산 완료 - 커서: {}", nextCursor);

            // 더보기 여부 확인을 위해 추가 조회
            List<NotificationListItem> nextPage = notificationRepository.findAfterCursor(userId, nextCursor, PageRequest.of(0, 1));
            hasMore = !nextPage.isEmpty();
            log.debug("더보기 여부 확인 완료 - hasMore: {}", hasMore);
        }

        // 전체 미읽음 개수 조회
        Long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);
        log.debug("미읽음 개수 조회 완료 - 개수: {}", unreadCount);

        log.info("알림 목록 조회 완료 - 사용자 ID: {}, 조회된 알림 수: {}, 미읽음 개수: {}", userId, notifications.size(), unreadCount);

        return NotificationListResponseDto.builder()
            .notifications(notifications)
            .cursor(nextCursor)
            .hasMore(hasMore)
            .unreadCount(unreadCount)
            .build();
    }

    /**
     * 개별 알림 읽음 처리
     *
     * 사용자가 선택한 특정 알림들을 읽음 상태로 변경합니다.
     * 권한 검증을 통해 본인의 알림만 처리하며,
     * 처리 후 SSE를 통해 실시간으로 읽지 않은 개수를 업데이트합니다.
     *
     * @param userId 사용자 ID (권한 검증용)
     * @param requestDto 읽음 처리할 알림 ID 목록
     * @return 처리 결과 정보
     *
     * 보안 고려사항:
     * - 다른 사용자의 알림은 처리되지 않음
     * - 이미 읽은 알림은 중복 처리하지 않음
     *
     * 성능 최적화:
     * - 벌크 업데이트로 한 번에 처리
     * - 실제 변경된 개수만 반환
     *
     * 실시간 업데이트:
     * - SSE를 통한 읽지 않은 개수 실시간 전송
     */
    @Transactional
    public NotificationReadResponseDto markAsRead(Long userId, NotificationReadRequestDto requestDto) {
        List<Long> notificationIds = requestDto.getNotificationIds();
        log.info("알림 읽음 처리 시작 - 사용자 ID: {}, 대상 알림 수: {}", userId, notificationIds.size());
        log.debug("읽음 처리 대상 알림 ID 목록: {}", notificationIds);

        // 해당 사용자의 알림만 조회하여 권한 검증
        List<Notification> notifications = notificationRepository.findByUserIdAndIds(userId, notificationIds);
        log.debug("권한 검증 완료 - 처리 가능한 알림 수: {}", notifications.size());

        if (notifications.size() != notificationIds.size()) {
            log.warn("일부 알림 접근 불가 - 요청: {}, 처리 가능: {}", notificationIds.size(), notifications.size());
        }

        // 읽지 않은 알림만 처리
        int updatedCount = 0;
        for (Notification notification : notifications) {
            if (!notification.getIsRead()) {
                notification.markAsRead();
                updatedCount++;
                log.debug("알림 읽음 처리 - 알림 ID: {}", notification.getNotificationId());
            }
        }

        log.info("알림 읽음 처리 완료 - 사용자 ID: {}, 실제 처리 수: {}", userId, updatedCount);

        // 읽지 않은 개수 실시간 업데이트
        if (updatedCount > 0) {
            try {
                sendUnreadCountUpdate(userId);
                log.debug("읽지 않은 개수 실시간 업데이트 전송 완료");
            } catch (Exception e) {
                log.error("읽지 않은 개수 실시간 업데이트 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage(), e);
            }
        }

        return NotificationReadResponseDto.builder()
            .updatedCount(updatedCount)
            .notificationIds(notificationIds)
            .build();
    }

    /**
     * 알림 전체 읽음 처리
     *
     * 사용자의 모든 읽지 않은 알림을 한 번에 읽음 처리합니다.
     * "모든 알림 읽음" 기능을 위한 메서드입니다.
     *
     * @param dto 전체 읽음 처리 요청 정보
     *
     * 성능 주의사항:
     * - 대량의 알림이 있는 경우 처리 시간이 오래 걸릴 수 있음
     * - 향후 배치 처리나 비동기 처리 고려 필요
     *
     */
    @Transactional
    public void markAllAsRead(NotificationListRequestDto dto) {
        Long userId = dto.getUserId();
        log.info("전체 알림 읽음 처리 시작 - 사용자 ID: {}", userId);

        // 읽지 않은 알림 목록 조회
        List<Notification> unreadList = notificationRepository.findByUser_UserIdAndIsReadFalse(userId);
        log.debug("읽지 않은 알림 조회 완료 - 개수: {}", unreadList.size());

        if (unreadList.isEmpty()) {
            log.info("읽지 않은 알림이 없음 - 사용자 ID: {}", userId);
            return;
        }

        // 전체 읽음 처리
        unreadList.forEach(notification -> {
            notification.markAsRead();
            log.debug("알림 읽음 처리 - 알림 ID: {}", notification.getNotificationId());
        });

        log.info("전체 알림 읽음 처리 완료 - 사용자 ID: {}, 처리된 알림 수: {}", userId, unreadList.size());

        // 읽지 않은 개수 실시간 업데이트
        try {
            sendUnreadCountUpdate(userId);
            log.debug("전체 읽음 후 실시간 업데이트 전송 완료");
        } catch (Exception e) {
            log.error("전체 읽음 후 실시간 업데이트 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 알림 삭제
     *
     * 사용자의 특정 알림을 삭제합니다.
     * 권한 검증을 통해 본인의 알림만 삭제할 수 있습니다.
     *
     * @param userId 사용자 ID (권한 검증용)
     * @param notificationId 삭제할 알림 ID
     *
     * 보안 고려사항:
     * - 다른 사용자의 알림은 삭제할 수 없음
     * - 존재하지 않는 알림 ID는 오류 처리
     *
     * 데이터 정합성:
     * - 삭제 후 읽지 않은 개수 자동 업데이트
     * - 관련 SSE 연결에 실시간 반영
     */
    @Transactional
    public void deleteNotification(Long userId, Long notificationId) {
        log.info("알림 삭제 시작 - 사용자 ID: {}, 알림 ID: {}", userId, notificationId);

        // 알림 조회 및 존재 여부 확인
        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> {
                log.error("알림 삭제 실패 - 존재하지 않는 알림 ID: {}", notificationId);
                return new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
            });

        // 권한 검증 (본인의 알림만 삭제 가능)
        if (!notification.getUser().getUserId().equals(userId)) {
            log.error("알림 삭제 권한 없음 - 요청 사용자: {}, 알림 소유자: {}", userId, notification.getUser().getUserId());
            throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND); // 보안상 동일한 오류 코드 사용
        }

        // 읽지 않은 알림인지 확인 (실시간 업데이트 최적화용)
        boolean wasUnread = !notification.getIsRead();
        log.debug("삭제 대상 알림 상태 - 읽음 여부: {}", notification.getIsRead());

        // 알림 삭제
        notificationRepository.delete(notification);
        log.info("알림 삭제 완료 - 사용자 ID: {}, 알림 ID: {}", userId, notificationId);

        // 읽지 않은 알림이었다면 개수 업데이트
        if (wasUnread) {
            try {
                sendUnreadCountUpdate(userId);
                log.debug("삭제 후 읽지 않은 개수 실시간 업데이트 전송 완료");
            } catch (Exception e) {
                log.error("삭제 후 실시간 업데이트 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage(), e);
            }
        }
    }

    /**
     * 알림 생성 이벤트 처리
     *
     * 외부에서 알림 생성 이벤트를 받아 SSE로 전송합니다.
     * 이벤트 기반 아키텍처에서 사용되는 메서드입니다.
     *
     * @param notification 생성된 알림 객체
     *
     * 사용 시나리오:
     */
    public void sendCreated(Notification notification) {
        log.info("알림 생성 이벤트 처리 - 알림 ID: {}, 사용자 ID: {}",
            notification.getNotificationId(), notification.getUser().getUserId());

        try {
            sendSseNotification(notification.getUser().getUserId(), notification);
            log.debug("이벤트 기반 SSE 알림 전송 완료");
        } catch (Exception e) {
            log.error("이벤트 기반 SSE 알림 전송 실패 - 알림 ID: {}, 오류: {}",
                notification.getNotificationId(), e.getMessage(), e);
        }
    }

    /**
     * 알림 읽음 이벤트 처리
     *
     * 외부에서 알림 읽음 이벤트를 받아 실시간 업데이트를 전송합니다.
     *
     * @param notification 읽음 처리된 알림 객체
     */
    public void sendRead(Notification notification) {
        log.info("알림 읽음 이벤트 처리 - 알림 ID: {}, 사용자 ID: {}",
            notification.getNotificationId(), notification.getUser().getUserId());

        try {
            sendUnreadCountUpdate(notification.getUser().getUserId());
            log.debug("이벤트 기반 읽지 않은 개수 업데이트 전송 완료");
        } catch (Exception e) {
            log.error("이벤트 기반 읽지 않은 개수 업데이트 실패 - 알림 ID: {}, 오류: {}",
                notification.getNotificationId(), e.getMessage(), e);
        }
    }

    /**
     * SSE를 통한 실시간 알림 전송
     *
     * 연결된 사용자에게 새로운 알림을 SSE로 실시간 전송합니다.
     * 연결이 끊어진 경우 자동으로 정리됩니다.
     *
     * @param userId 알림을 받을 사용자 ID
     * @param notification 전송할 알림 객체
     *
     */
    private void sendSseNotification(Long userId, Notification notification) {
        SseEmitter emitter = sseEmitters.get(userId);

        if (emitter == null) {
            log.debug("SSE 연결 없음 - 사용자 ID: {} (알림 전송 생략)", userId);
            return;
        }

        try {
            SseNotificationDto sseDto = SseNotificationDto.from(notification);
            emitter.send(SseEmitter.event()
                .name("notification")
                .data(sseDto));

            log.info("SSE 알림 전송 성공 - 사용자 ID: {}, 알림 ID: {}", userId, notification.getNotificationId());
        } catch (IOException e) {
            log.error("SSE 알림 전송 실패 - 사용자 ID: {}, 알림 ID: {}, 오류: {}",
                userId, notification.getNotificationId(), e.getMessage());

            // 전송 실패한 연결은 정리
            sseEmitters.remove(userId);
            log.debug("전송 실패로 인한 SSE 연결 정리 완료 - 사용자 ID: {}", userId);
        }
    }

    /**
     * 읽지 않은 개수 실시간 업데이트
     *
     * 사용자의 읽지 않은 알림 개수 변경 시 SSE로 실시간 전송합니다.
     * 알림 읽음 처리나 삭제 후 자동으로 호출됩니다.
     *
     * @param userId 업데이트할 사용자 ID
     *
     */
    private void sendUnreadCountUpdate(Long userId) {
        SseEmitter emitter = sseEmitters.get(userId);

        if (emitter == null) {
            log.debug("SSE 연결 없음 - 사용자 ID: {} (개수 업데이트 생략)", userId);
            return;
        }

        try {
            Long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);
            emitter.send(SseEmitter.event()
                .name("unread_count")
                .data(Map.of("unread_count", unreadCount)));

            log.debug("읽지 않은 개수 업데이트 전송 성공 - 사용자 ID: {}, 개수: {}", userId, unreadCount);
        } catch (IOException e) {
            log.error("읽지 않은 개수 업데이트 전송 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage());

            // 전송 실패한 연결은 정리
            sseEmitters.remove(userId);
            log.debug("개수 업데이트 실패로 인한 SSE 연결 정리 완료 - 사용자 ID: {}", userId);
        }
    }

    /**
     * 하트비트 전송 (연결 유지)
     *
     * 모든 활성 SSE 연결에 하트비트를 전송하여 연결 상태를 확인합니다.
     * 스케줄러에 의해 주기적으로 호출되어야 합니다.
     *
     */
    public void sendHeartbeat() {
        log.debug("하트비트 전송 시작 - 대상 연결 수: {}", sseEmitters.size());

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        sseEmitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                    .comment("heartbeat"));
                log.trace("하트비트 전송 성공 - 사용자 ID: {}", userId);
                successCount.getAndIncrement();
            } catch (IOException e) {
                log.warn("하트비트 전송 실패 - 사용자 ID: {}, 연결 정리", userId);
                sseEmitters.remove(userId);
                failureCount.getAndIncrement();
            }
        });

        log.info("하트비트 전송 완료 - 성공: {}, 실패: {}, 남은 연결: {}",
            successCount, failureCount, sseEmitters.size());
    }

    /**
     * NotificationListItem을 NotificationItemDto로 변환
     *
     * 프로젝션 객체를 API 응답용 DTO로 변환합니다.
     * 내부적으로만 사용되는 유틸리티 메서드입니다.
     *
     * @param item 프로젝션 객체
     * @return API 응답용 DTO
     */
    private NotificationItemDto convertToItemDto(NotificationListItem item) {
        return NotificationItemDto.builder()
            .notificationId(item.getNotificationId())
            .content(item.getContent())
            .type(item.getType())
            .isRead(item.getIsRead())
            .createdAt(item.getCreatedAt())
            .build();
    }

    /**
     * 활성 SSE 연결 수 조회 (모니터링용)
     *
     * 현재 활성 상태인 SSE 연결의 개수를 반환합니다.
     * 시스템 모니터링이나 관리자 대시보드에서 활용할 수 있습니다.
     *
     * @return 현재 활성 연결 수
     *
     * 모니터링 활용:
     * - 서버별 동시 연결 수 추적
     * - 피크 시간대 연결 패턴 분석
     * - 메모리 사용량 예측
     */
    public int getActiveConnectionCount() {
        int count = sseEmitters.size();
        log.debug("현재 활성 SSE 연결 수 조회: {}", count);
        return count;
    }

    // ================================
    // 향후 확장 가능한 메서드들 (주석으로 기록)
    // ================================

    /**
     * FCM을 통한 푸시 알림 전송 (향후 구현)
     *
     * @param notification 전송할 알림
     */
    // private void sendFcmNotification(Notification notification) {
    //     log.info("FCM 알림 전송 시작 - 알림 ID: {}", notification.getNotificationId());
    //     // FCM 전송 로직 구현
    // }

    /**
     * 알림 전송 실패 재시도 (향후 구현)
     *
     * @param userId 사용자 ID
     */
    // @Async
    // public void retryFailedNotifications(Long userId) {
    //     log.info("실패한 알림 재전송 시작 - 사용자 ID: {}", userId);
    //     // 재전송 로직 구현
    // }

    /**
     * 사용자별 알림 설정 확인 (향후 구현)
     *
     * @param userId 사용자 ID
     * @param type 알림 타입
     * @return 알림 수신 허용 여부
     */
    // private boolean isNotificationEnabled(Long userId, Type type) {
    //     // 사용자 알림 설정 확인 로직
    //     return true;
    // }
}