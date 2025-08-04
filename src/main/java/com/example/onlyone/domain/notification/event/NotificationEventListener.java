package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

  private final NotificationService notificationService;

  /**
   * 알림 생성 이벤트 처리
   */
  @EventListener
  @Async
  public void onNotificationCreated(NotificationCreatedEvent event) {
    Long notificationId = event.getNotification().getNotificationId();
    try {
      log.debug("새 알림 생성 완료. 알림 ID={}", notificationId);
      notificationService.sendCreated(event.getNotification());
    } catch (Exception e) {
      log.error("알림 생성 후 발송 처리에 실패했습니다. 알림 ID={}", notificationId, e);
    }
  }

  /**
   * 알림 읽음 이벤트 처리
   */
  @EventListener
  @Async
  public void onNotificationRead(NotificationReadEvent event) {
    Long notificationId = event.getNotification().getNotificationId();
    try {
      log.debug("알림 읽음 처리 완료. 알림 ID={}", notificationId);
      notificationService.sendRead(event.getNotification());
    } catch (Exception e) {
      log.error("알림 읽음 처리 후 전송에 실패했습니다. 알림 ID={}", notificationId, e);
    }
  }
}