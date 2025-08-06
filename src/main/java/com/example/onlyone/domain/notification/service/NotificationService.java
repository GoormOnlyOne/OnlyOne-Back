package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.*;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationListItem;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
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


 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SseEmittersService sseEmittersService;
    private final UserRepository userRepository;
    private final NotificationTypeRepository notificationTypeRepository;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final FcmService fcmService;



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
     * 5. FCM 전송
     *
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
            sseEmittersService.sendSseNotification(requestDto.getUserId(), savedNotification);
            log.debug("SSE 알림 전송 완료 - 알림 ID: {}", savedNotification.getNotificationId());
        } catch (Exception e) {
            log.error("SSE 알림 전송 실패 - 알림 ID: {}, 오류: {}", savedNotification.getNotificationId(), e.getMessage(), e);
            // SSE 전송 실패해도 알림 생성은 성공으로 처리
        }

         try {
             fcmService.sendFcmNotification(savedNotification);
             savedNotification.markFcmSent(true);
             log.debug("FCM 알림 전송 완료 - 알림 ID: {}", savedNotification.getNotificationId());
         } catch (Exception e) {
             log.error("FCM 알림 전송 실패 - 알림 ID: {}, 오류: {}", savedNotification.getNotificationId(), e.getMessage(), e);
             savedNotification.markFcmSent(false);
         }

        return NotificationCreateResponseDto.from(savedNotification);
    }

    /**
     * 알림 목록 조회 (커서 기반 페이징)
     *
     * 사용자의 알림 목록을 커서 기반 페이징으로 조회합니다.
     * 읽지 않은 알림 개수도 함께 반환합니다.
     *
     * @param userId 사용자 ID
     * @param cursor 페이징 커서 (이전 페이지의 마지막 알림 ID)
     * @param size 페이지 크기
     * @return 알림 목록과 페이징 정보
     *
     * 페이징 전략:
     * - 최신 알림부터 내림차순 정렬
     * - hasMore 플래그로 추가 데이터 존재 여부 확인
     *
     */
    @Transactional(readOnly = true)
    public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
        log.info("알림 목록 조회 시작 - 사용자 ID: {}, 커서: {}, 크기: {}", userId, cursor, size);

        // 페이지 크기 제한
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
            List<NotificationListItem> items = notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, pageable);
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
     * 알림 전체 읽음 처리
     *
     * 사용자의 모든 읽지 않은 알림을 한 번에 읽음 처리합니다.
     * "모든 알림 읽음" 기능을 위한 메서드입니다.
     *
     */
    @Transactional
    public void markAllAsRead(Long userId) {

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
            sseEmittersService.sendUnreadCountUpdate(userId);
            log.debug("전체 읽음 후 실시간 업데이트 전송 완료");
        } catch (Exception e) {
            log.error("전체 읽음 후 실시간 업데이트 실패 - 사용자 ID: {}, 오류: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 알림 삭제
     *
     * 사용자의 특정 알림을 삭제합니다.
     *
     * @param userId 사용자 ID (권한 검증용)
     * @param notificationId 삭제할 알림 ID
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
                sseEmittersService.sendUnreadCountUpdate(userId);
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
            sseEmittersService.sendSseNotification(notification.getUser().getUserId(), notification);
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
            sseEmittersService.sendUnreadCountUpdate(notification.getUser().getUserId());
            log.debug("이벤트 기반 읽지 않은 개수 업데이트 전송 완료");
        } catch (Exception e) {
            log.error("이벤트 기반 읽지 않은 개수 업데이트 실패 - 알림 ID: {}, 오류: {}",
                notification.getNotificationId(), e.getMessage(), e);
        }
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

}