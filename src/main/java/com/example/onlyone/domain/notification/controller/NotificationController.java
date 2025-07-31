package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.*;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 알림 컨트롤러
 *
 * 알림 시스템의 REST API 엔드포인트를 제공하는 컨트롤러 계층입니다.
 * SSE(Server-Sent Events)를 통한 실시간 알림 스트리밍과
 * 전통적인 REST API를 통한 알림 관리 기능을 모두 지원합니다.
 *
 * 주요 기능:
 * 1. SSE 실시간 알림 스트림 연결
 * 2. 알림 생성 (테스트/관리용)
 * 3. 커서 기반 페이징을 통한 알림 목록 조회
 * 4. 개별/일괄 알림 읽음 처리
 * 5. 알림 삭제 (향후 구현)
 *
 * API 설계 원칙:
 * - RESTful 설계 준수
 * - 일관된 응답 포맷 (CommonResponse)
 * - 적절한 HTTP 상태 코드 사용
 * - 보안을 위한 JWT 토큰 검증
 *
 * 확장성 고려사항:
 * - 버전 관리 (v1, v2 등)
 * - 요청/응답 로깅 및 모니터링
 * - 요청 제한 (Rate Limiting)
 * - 캐싱 전략
 */
@Tag(name = "알림", description = "실시간 알림 및 알림 관리 API")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * SSE 스트림 연결
     * 클라이언트와 서버 간 실시간 알림을 위한 SSE 연결을 생성합니다. 연결 생성 후 하트비트, 알림, 읽지 않은 개수 업데이트 등의 이벤트를 실시간으로 수신할 수
     * 있습니다.
     * SSE 이벤트 타입: - heartbeat: 연결 유지 확인 - notification: 새로운 알림 도착 - unread_count: 읽지 않은 개수 변경
     *
     * @param authorization JWT 토큰 (Bearer 형식)
     * @return SSE Emitter 객체
     *
     * 클라이언트 연결 예시: ```javascript const eventSource = new EventSource('/notifications/stream', {
     * headers: { 'Authorization': 'Bearer ' + token } });
     *
     * eventSource.addEventListener('notification', (event) => { const notification =
     * JSON.parse(event.data); // 알림 UI 업데이트 }); ```
     *
     * 연결 관리: - 클라이언트 연결 해제 시 자동 정리 - 서버 재시작 시 모든 연결 끊김 (재연결 필요) - 네트워크 오류 시 자동 재연결 권장
     */
    @Operation(summary = "알림 실시간 스트림", description = "Server-Sent Events를 통한 실시간 알림 수신")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
        @RequestHeader("Authorization") String authorization) {

        log.info("SSE 스트림 연결 요청 - Authorization: {}", maskToken(authorization));

        try {
            // JWT 토큰에서 userId 추출
            Long userId = extractUserIdFromToken(authorization);
            log.info("SSE 연결 생성 시작 - 사용자 ID: {}", userId);

            SseEmitter emitter = notificationService.createSseConnection(userId);
            log.info("SSE 연결 생성 완료 - 사용자 ID: {}", userId);

            return emitter;
        } catch (Exception e) {
            log.error("SSE 연결 생성 실패 - Authorization: {}, 오류: {}",
                maskToken(authorization), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 알림 생성
     *
     * 새로운 알림을 생성합니다. 주로 테스트나 관리자 기능에서 사용되며,
     * 실제 비즈니스 로직에서는 도메인 이벤트를 통해 알림이 생성됩니다.
     *
     * 생성 프로세스:
     * 1. 요청 데이터 검증
     * 2. 사용자 및 알림 타입 존재 확인
     * 3. 템플릿 기반 메시지 생성
     * 4. 데이터베이스 저장
     * 5. SSE를 통한 실시간 전송
     * 6. FCM 전송 (향후)
     *
     * @param requestDto 알림 생성 요청 정보
     * @return 생성된 알림 정보
     *
     * 요청 예시:
     * ```json
     * {
     *   "userId": 1,
     *   "type": "CHAT",
     *   "args": ["홍길동"]
     * }
     * ```
     *
     * 보안 고려사항:
     * - 관리자 권한 검증 필요 (향후 구현)
     * - 스팸 방지를 위한 요청 제한
     * - 입력 데이터 검증 및 sanitization
     */
    @Operation(summary = "알림 생성", description = "새로운 알림을 생성합니다 (테스트/관리용)")
    @PostMapping
    public ResponseEntity<?> createNotification(
        @Valid @RequestBody NotificationCreateRequestDto requestDto) {

        log.info("알림 생성 요청 - 사용자 ID: {}, 타입: {}, 파라미터 수: {}",
            requestDto.getUserId(), requestDto.getType(),
            requestDto.getArgs() != null ? requestDto.getArgs().length : 0);
        log.debug("알림 생성 요청 상세 - 요청 데이터: {}", requestDto);

        try {
            NotificationCreateResponseDto responseDto =
                notificationService.createNotification(requestDto);

            log.info("알림 생성 성공 - 알림 ID: {}, 사용자 ID: {}",
                responseDto.getNotificationId(), requestDto.getUserId());

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CommonResponse.success(responseDto));

        } catch (Exception e) {
            log.error("알림 생성 실패 - 사용자 ID: {}, 타입: {}, 오류: {}",
                requestDto.getUserId(), requestDto.getType(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 알림 목록 조회 (커서 기반 페이징)
     *
     * 사용자의 알림 목록을 커서 기반 페이징으로 조회합니다. 최신 알림부터 내림차순으로 정렬되며, 읽지 않은 알림 개수도 함께 반환합니다.
     *
     * 페이징 방식: - 첫 페이지: cursor 파라미터 없이 요청 - 다음 페이지: 이전 응답의 cursor 값을 사용 - hasMore가 false면 더 이상 데이터 없음
     *
     * @param authorization JWT 토큰
     * @param cursor        페이징 커서 (이전 페이지의 마지막 알림 ID)
     * @param size          페이지 크기 (기본값: 20, 최대: 100)
     * @return 알림 목록과 페이징 정보
     *
     * 응답 예시: ```json { "notifications": [...], "cursor": 12345, "hasMore": true, "unreadCount": 5 }
     * ```
     *
     * 성능 최적화: - 프로젝션을 통한 필요한 필드만 조회 - 인덱스 기반 커서 페이징으로 일관된 성능 - 캐싱 가능한 읽기 전용 트랜잭션
     */
    @Operation(summary = "알림 목록 조회", description = "커서 기반 페이징으로 알림 목록을 조회합니다")
    @GetMapping
    public ResponseEntity<?> getNotifications(
        @RequestHeader("Authorization") String authorization,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "20") int size) {

        log.info("알림 목록 조회 요청 - 커서: {}, 크기: {}", cursor, size);

        try {
            // JWT 토큰에서 userId 추출
            Long userId = extractUserIdFromToken(authorization);
            log.debug("알림 목록 조회 - 사용자 ID: {}, 커서: {}, 크기: {}", userId, cursor, size);

            NotificationListResponseDto responseDto =
                notificationService.getNotifications(userId, cursor, size);

            log.info("알림 목록 조회 성공 - 사용자 ID: {}, 조회된 알림 수: {}, 미읽음 개수: {}",
                userId, responseDto.getNotifications().size(), responseDto.getUnreadCount());
            log.debug("알림 목록 조회 결과 - 다음 커서: {}, 더보기: {}",
                responseDto.getCursor(), responseDto.isHasMore());

            return ResponseEntity.ok(CommonResponse.success(responseDto));

        } catch (Exception e) {
            log.error("알림 목록 조회 실패 - 커서: {}, 크기: {}, 오류: {}", cursor, size, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 개별 알림 읽음 처리
     *
     * 사용자가 선택한 특정 알림들을 읽음 상태로 변경합니다. 읽음 처리 후 SSE를 통해 읽지 않은 개수가 실시간으로 업데이트됩니다.
     *
     * 처리 특징: - 본인의 알림만 처리 가능 (권한 검증) - 이미 읽은 알림은 중복 처리하지 않음 - 벌크 처리로 성능 최적화 - 실시간 UI 업데이트 지원
     *
     * @param authorization JWT 토큰
     * @param requestDto    읽음 처리할 알림 ID 목록
     * @return 처리 결과 정보
     *
     * 요청 예시: ```json { "notificationIds": [1, 2, 3, 4, 5] } ```
     *
     * 응답 예시: ```json { "updatedCount": 3, "notificationIds": [1, 2, 3, 4, 5] } ```
     *
     * 비즈니스 규칙: - 존재하지 않는 알림 ID는 무시 - 다른 사용자의 알림은 처리되지 않음 - 처리된 개수와 요청된 개수가 다를 수 있음
     */
    @Operation(summary = "알림 읽음 처리", description = "선택된 알림들을 읽음 처리합니다")
    @PatchMapping("/read")
    public ResponseEntity<?> markNotificationsAsRead(
        @RequestHeader("Authorization") String authorization,
        @Valid @RequestBody NotificationReadRequestDto requestDto) {

        log.info("알림 읽음 처리 요청 - 대상 알림 수: {}", requestDto.getNotificationIds().size());
        log.debug("읽음 처리 대상 알림 ID 목록: {}", requestDto.getNotificationIds());

        try {
            // JWT 토큰에서 userId 추출
            Long userId = extractUserIdFromToken(authorization);
            log.debug("알림 읽음 처리 - 사용자 ID: {}", userId);

            NotificationReadResponseDto responseDto =
                notificationService.markAsRead(userId, requestDto);

            log.info("알림 읽음 처리 성공 - 사용자 ID: {}, 요청 수: {}, 실제 처리 수: {}",
                userId, requestDto.getNotificationIds().size(), responseDto.getUpdatedCount());

            return ResponseEntity.ok(CommonResponse.success(responseDto));

        } catch (Exception e) {
            log.error("알림 읽음 처리 실패 - 대상 알림 수: {}, 오류: {}",
                requestDto.getNotificationIds().size(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 알림 삭제
     *
     * 사용자의 특정 알림을 삭제합니다. 본인의 알림만 삭제할 수 있으며, 삭제 후 읽지 않은 개수가 자동으로 업데이트됩니다.
     *
     * 현재 상태: 기본 구현 완료 (향후 UI에서 활용)
     *
     * @param authorization  JWT 토큰
     * @param notificationId 삭제할 알림 ID
     * @return 성공 시 204 No Content
     *
     * 보안 고려사항: - 본인의 알림만 삭제 가능 - 존재하지 않는 알림 ID는 404 오류 - 다른 사용자의 알림 접근 시 404 오류 (보안상)
     *
     * 향후 확장 가능성: - 논리 삭제(soft delete) 지원 - 삭제 후 일정 기간 복구 기능 - 일괄 삭제 기능 - 삭제 전 확인 절차
     */
    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<?> deleteNotification(
        @RequestHeader("Authorization") String authorization,
        @PathVariable Long notificationId) {

        log.info("알림 삭제 요청 - 알림 ID: {}", notificationId);

        try {
            // JWT 토큰에서 userId 추출
            Long userId = extractUserIdFromToken(authorization);
            log.debug("알림 삭제 - 사용자 ID: {}, 알림 ID: {}", userId, notificationId);

            notificationService.deleteNotification(userId, notificationId);

            log.info("알림 삭제 성공 - 사용자 ID: {}, 알림 ID: {}", userId, notificationId);

            return ResponseEntity.noContent().build();

        } catch (Exception e) {
            log.error("알림 삭제 실패 - 알림 ID: {}, 오류: {}", notificationId, e.getMessage(), e);
            throw e;
        }
    }

    // ================================
    // 유틸리티 메서드들
    // ================================

    /**
     * JWT 토큰에서 사용자 ID 추출
     *
     * Authorization 헤더의 JWT 토큰을 파싱하여 사용자 ID를 추출합니다. 현재는 임시 구현이며, 실제 JWT 파싱 로직으로 교체해야 합니다.
     *
     * @param authorization Authorization 헤더 값 (Bearer 토큰)
     * @return 추출된 사용자 ID
     *
     * 구현 예정 사항: - JWT 라이브러리를 통한 토큰 파싱 - 토큰 만료 검증 - 토큰 서명 검증 - 권한 정보 추출 - 예외 처리 (토큰 없음, 잘못된 형식, 만료 등)
     *
     * 보안 고려사항: - 토큰 정보 로깅 시 마스킹 처리 - 민감한 정보 노출 방지 - 토큰 재사용 공격 방지
     */
    private Long extractUserIdFromToken(String authorization) {
        log.debug("JWT 토큰에서 사용자 ID 추출 시작");

        // TODO: 실제 JWT 파싱 로직 구현
        // 예시 구현:
        // try {
        //     String token = authorization.replace("Bearer ", "");
        //     Claims claims = Jwts.parser()
        //         .setSigningKey(jwtSecret)
        //         .parseClaimsJws(token)
        //         .getBody();
        //     Long userId = claims.get("userId", Long.class);
        //     log.debug("JWT 토큰 파싱 성공 - 사용자 ID: {}", userId);
        //     return userId;
        // } catch (JwtException e) {
        //     log.error("JWT 토큰 파싱 실패: {}", e.getMessage());
        //     throw new CustomException(ErrorCode.INVALID_TOKEN);
        // }

        // 임시 구현 (개발용)
        Long temporaryUserId = 1L;
        log.warn("임시 사용자 ID 사용 - ID: {} (실제 JWT 파싱 로직 구현 필요)", temporaryUserId);

        return temporaryUserId;
    }

    /**
     * 토큰 정보 마스킹 처리
     *
     * 로깅 시 토큰 정보를 안전하게 마스킹 처리합니다. 보안상 토큰의 일부만 표시하고 나머지는 * 처리합니다.
     *
     * @param token 원본 토큰
     * @return 마스킹 처리된 토큰
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "***";
        }

        // Bearer 토큰의 경우 앞 10자리만 표시
        if (token.startsWith("Bearer ")) {
            String actualToken = token.substring(7);
            if (actualToken.length() < 10) {
                return "Bearer ***";
            }
            return "Bearer " + actualToken.substring(0, 10) + "***";
        }

        // 일반 토큰의 경우 앞 10자리만 표시
        return token.substring(0, 10) + "***";
    }
}