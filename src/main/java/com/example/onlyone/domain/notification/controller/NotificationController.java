package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
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
 *
 * 주요 기능:
 * 1. SSE 실시간 알림 스트림 연결
 * 2. 알림 생성 (테스트/관리용)
 * 3. 커서 기반 페이징을 통한 알림 목록 조회
 * 4. 일괄 알림 읽음 처리
 * 5. 알림 삭제 (향후 구현)
 *

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
     * 클라이언트와 서버 간 실시간 알림을 위한 SSE 연결을 생성합니다.
     * SSE 이벤트 타입: - heartbeat: 연결 유지 확인 - notification: 새로운 알림 도착 - unread_count: 읽지 않은 개수 변경
     *
     * @return SSE Emitter 객체
     *
     */
    @Operation(summary = "알림 실시간 스트림", description = "Server-Sent Events를 통한 실시간 알림 수신")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(@RequestParam Long userId) {
        log.info("SSE 스트림 연결 요청 - 사용자 ID: {}", userId);
        // 서비스에서 실패 시 CustomException을 던지고, GlobalExceptionHandler가 처리해 줍니다.
        return notificationService.createSseConnection(userId);
    }

    /**
     * 알림 생성
     *
     * 새로운 알림을 생성합니다.
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
     */
    @Operation(summary = "알림 생성", description = "새로운 알림을 생성합니다")
    @PostMapping
    public ResponseEntity<?> createNotification(
        @Valid @RequestBody NotificationCreateRequestDto requestDto) {

        log.info("알림 생성 요청 - 사용자 ID: {}, 타입: {}",
            requestDto.getUserId(), requestDto.getType());

        NotificationCreateResponseDto responseDto =
            notificationService.createNotification(requestDto);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(CommonResponse.success(responseDto));
    }

    /**
     * 알림 목록 조회 (커서 기반 페이징)
     *
     * 사용자의 알림 목록을 커서 기반 페이징으로 조회합니다. 최신 알림부터 내림차순으로 정렬되며, 읽지 않은 알림 개수도 함께 반환합니다.
     *
     * 페이징 방식: - 첫 페이지: cursor 파라미터 없이 요청 - 다음 페이지: 이전 응답의 cursor 값을 사용 - hasMore가 false면 더 이상 데이터 없음
     *
     * @param cursor        페이징 커서 (이전 페이지의 마지막 알림 ID)
     * @param size          페이지 크기 (기본값: 20, 최대: 100)
     * @return 알림 목록과 페이징 정보
     *
     * 응답 예시: ```json { "notifications": [...], "cursor": 12345, "hasMore": true, "unreadCount": 5 }
     * ```
     */
    @Operation(summary = "알림 목록 조회", description = "커서 기반 페이징으로 알림 목록을 조회합니다")
    @GetMapping
    public ResponseEntity<CommonResponse<NotificationListResponseDto>> getNotifications(
        @RequestParam Long userId,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "20") int size) {

        if (size > 100) {
            size = 100;
            log.warn("요청된 페이지 크기가 최대값을 초과하여 100으로 조정됨");
        }

        NotificationListResponseDto dto =
            notificationService.getNotifications(userId, cursor, size);

        return ResponseEntity.ok(CommonResponse.success(dto));
    }

    /**
     * 모든 알림 읽음 처리
     *
     * 사용자의 모든 읽지 않은 알림을 일괄적으로 읽음 처리합니다.
     *
     * @param userId 사용자 ID
     * @return 처리 결과 정보
     */
    @Operation(summary = "모든 알림 읽음 처리", description = "모든 읽지 않은 알림을 읽음 처리합니다")
    @PatchMapping("/read-all")
    public ResponseEntity<CommonResponse<Void>> markAllNotificationsAsRead(
        @RequestParam Long userId) {

        notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }
    /**
     * 알림 삭제
     *
     * 사용자의 특정 알림을 삭제합니다. 본인의 알림만 삭제할 수 있으며, 삭제 후 읽지 않은 개수가 자동으로 업데이트됩니다.
     *
     * 현재 상태: 기본 구현 완료 (향후 UI에서 활용)
     *
     * @param notificationId 삭제할 알림 ID
     * @return 성공 시 204 No Content
     *
     */
    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(
        @RequestParam Long userId,
        @PathVariable Long notificationId) {

        notificationService.deleteNotification(userId, notificationId);
        return ResponseEntity.noContent().build();
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
//    private Long extractUserIdFromToken(String authorization) {
//        log.debug("JWT 토큰에서 사용자 ID 추출 시작");
//
//        // TODO: 실제 JWT 파싱 로직 구현
//         예시 구현:
//         try {
//             String token = authorization.replace("Bearer ", "");
//             Claims claims = Jwts.parser()
//                 .setSigningKey(jwtSecret)
//                 .parseClaimsJws(token)
//                 .getBody();
//             Long userId = claims.get("userId", Long.class);
//             log.debug("JWT 토큰 파싱 성공 - 사용자 ID: {}", userId);
//             return userId;
//         } catch (JwtException e) {
//             log.error("JWT 토큰 파싱 실패: {}", e.getMessage());
//             throw new CustomException(ErrorCode.INVALID_TOKEN);
//         }
//
//         임시 구현 (개발용)
//        Long temporaryUserId = 1L;
//        log.warn("임시 사용자 ID 사용 - ID: {} (실제 JWT 파싱 로직 구현 필요)", temporaryUserId);
//
//        return temporaryUserId;
//    }

}