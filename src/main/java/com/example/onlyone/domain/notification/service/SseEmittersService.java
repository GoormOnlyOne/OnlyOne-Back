package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.SseNotificationDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RequiredArgsConstructor
@Service
public class SseEmittersService {

  /**
   * SSE 연결 관리 맵
   * <p>
   * 메모리 기반으로 사용자별 SSE 연결을 관리합니다. Key: 사용자 ID, Value: SSE Emitter 객체
   * <p>
   * <p>
   * 제한사항: - 단일 서버 환경에서만 동작 - 서버 재시작 시 모든 연결 끊어짐
   */

  private final Map<Long, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
  private final SseEmitterFactory emitterFactory;
  private final NotificationRepository notificationRepository;


  /**
   * SSE 연결 생성 및 관리
   * <p>
   * 클라이언트와의 SSE 연결을 생성하고 생명주기를 관리합니다. 연결 생성 즉시 하트비트를 전송하여 연결 상태를 확인합니다.
   *
   * @param userId 연결을 요청한 사용자 ID
   * @return 생성된 SSE Emitter 객체
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

    SseEmitter emitter = emitterFactory.create(0L); // 무제한 타임아웃
    sseEmitters.put(userId, emitter);

    log.info("SSE 연결 생성 완료 - 사용자 ID: {}, 현재 연결 수: {}", userId, sseEmitters.size());

    // 연결 완료 시 정리 콜백 등록
    emitter.onCompletion(() -> {
      log.info("SSE 연결 정상 종료 - 사용자 ID: {}", userId);
      sseEmitters.remove(userId);
      log.debug("연결 정리 완료 - 남은 연결 수: {}", sseEmitters.size());
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
   * SSE를 통한 실시간 알림 전송
   *
   * 연결된 사용자에게 새로운 알림을 SSE로 실시간 전송합니다.
   * 연결이 끊어진 경우 자동으로 정리됩니다.
   *
   * @param userId 알림을 받을 사용자 ID
   * @param notification 전송할 알림 객체
   *
   */
  public void sendSseNotification(Long userId, Notification notification) {
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
  public void sendUnreadCountUpdate(Long userId) {
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
}

