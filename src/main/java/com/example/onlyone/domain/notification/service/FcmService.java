package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.model.FcmNotificationTask;
import com.example.onlyone.domain.notification.model.BatchSendResult;
import com.example.onlyone.domain.notification.dto.fcm.FcmPriority;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * FCM 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService implements InitializingBean, DisposableBean {

  @Value("${app.notification.fcm.batch-size:500}")
  private int batchSize;
  
  @Value("${app.notification.fcm.max-retry:3}")
  private int maxRetry;

  private final FirebaseMessaging firebaseMessaging;
  private final NotificationRepository notificationRepository;
  private final PriorityBlockingQueue<FcmNotificationTask> priorityQueue = new PriorityBlockingQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
  private final ExecutorService queueProcessor = Executors.newFixedThreadPool(2);

  /**
   * FCM 알림 전송
   */
  public void sendFcmNotification(AppNotification appNotification) {
    try {
      String token = validateAndGetToken(appNotification);
      Message message = buildMessage(appNotification, token);

      String response = firebaseMessaging.send(message);
      log.info("FCM sent successfully: response={}, notificationId={}",
          response, appNotification.getId());

    } catch (IllegalArgumentException e) {
      // 토큰 예외
      throw new CustomException(ErrorCode.FCM_TOKEN_NOT_FOUND);

    } catch (FirebaseMessagingException e) {
      // Firebase 예외
      if (isInvalidTokenError(e)) {
        throw new CustomException(ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
      }
      
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);

    } catch (Exception e) {
      // 기타 예외
      log.error("Unexpected error during FCM send: notificationId={}, error={}", 
                appNotification.getId(), e.getMessage(), e);
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);
    }
  }
  
  /**
   * 토큰 에러 확인
   */
  private boolean isInvalidTokenError(FirebaseMessagingException e) {
    if (e.getErrorCode() == null) return false;
    
    String errorCode = e.getErrorCode().toString();
    return "INVALID_ARGUMENT".equals(errorCode) || 
           "UNREGISTERED".equals(errorCode) ||
           "INVALID_REGISTRATION".equals(errorCode);
  }

  /**
   * FCM 작업 큐 추가
   */
  public void queueFcmNotification(AppNotification appNotification, FcmPriority priority) {
    FcmNotificationTask task = FcmNotificationTask.of(appNotification, priority);
    priorityQueue.offer(task);
    
    log.debug("FCM task queued: notificationId={}, priority={}", 
             appNotification.getId(), priority);
  }

  /**
   * 배치 FCM 전송
   */
  public CompletableFuture<BatchSendResult> sendBatch(List<AppNotification> notifications) {
    if (notifications.isEmpty()) {
      return CompletableFuture.completedFuture(BatchSendResult.empty());
    }

    return CompletableFuture.supplyAsync(() -> {
      List<List<AppNotification>> batches = createBatches(notifications, batchSize);
      AtomicLong successCount = new AtomicLong(0);
      AtomicLong failureCount = new AtomicLong(0);

      batches.parallelStream().forEach(batch -> {
        try {
          MulticastMessage multicastMessage = buildMulticastMessage(batch);
          BatchResponse response = firebaseMessaging.sendMulticast(multicastMessage);
          
          successCount.addAndGet(response.getSuccessCount());
          failureCount.addAndGet(response.getFailureCount());
          
          handleBatchResponse(batch, response);
          
        } catch (FirebaseMessagingException e) {
          log.error("Batch FCM send failed: batchSize={}, error={}", batch.size(), e.getMessage(), e);
          failureCount.addAndGet(batch.size());
          
        } catch (Exception e) {
          log.error("Unexpected error during batch FCM send: batchSize={}, error={}", batch.size(), e.getMessage(), e);
          failureCount.addAndGet(batch.size());
        }
      });

      return BatchSendResult.of(successCount.get(), failureCount.get());
    });
  }

  /**
   * FCM 재전송
   */
  @Async
  @Transactional(readOnly = true)
  public void retryFailedNotifications(Long userId) {
    log.info("Starting FCM retry for user: {}", userId);

    try {
      List<AppNotification> failedNotifications = notificationRepository
              .findFailedFcmNotificationsByUserId(userId);

      if (failedNotifications.isEmpty()) {
        log.info("No failed notifications to retry for user: {}", userId);
        return;
      }

      sendBatch(failedNotifications).thenAccept(result -> {
        log.info("FCM retry completed: user={}, total={}, success={}, failed={}", 
                userId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
      });

    } catch (Exception e) {
      log.error("FCM retry failed for user: {}, error={}", userId, e.getMessage(), e);
      // 재시도 실패는 비즘이스 로직에 영향 주지 않으므로 예외를 던지지 않음
    }
  }

  /**
   * 서비스 초기화
   */
  @Override
  public void afterPropertiesSet() {
    // 큐 소비자 스레드 시작
    for (int i = 0; i < 2; i++) {
      queueProcessor.submit(this::processQueuedNotifications);
    }
    log.info("FCM priority queue processors started");
  }

  /**
   * 서비스 종료
   */
  @Override
  public void destroy() {
    log.info("Shutting down FCM service...");
    
    queueProcessor.shutdown();
    scheduler.shutdown();
    
    try {
      if (!queueProcessor.awaitTermination(10, TimeUnit.SECONDS)) {
        queueProcessor.shutdownNow();
      }
      if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      queueProcessor.shutdownNow();
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    
    log.info("FCM service shutdown completed");
  }

  /**
   * FCM 작업 처리
   */
  private void processQueuedNotifications() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        // 작업 가져오기
        FcmNotificationTask task = priorityQueue.take();
        
        // FCM 전송
        sendFcmNotification(task.getNotification());
        
      } catch (InterruptedException e) {
        log.info("FCM queue processor interrupted");
        Thread.currentThread().interrupt();
        break;
      } catch (CustomException e) {
        log.error("CustomException in FCM queue processing: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
        // 에러 시 대기
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      } catch (Exception e) {
        log.error("Unexpected error processing FCM queue", e);
        // 에러 시 대기
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  // FCM 토큰 검증
  private String validateAndGetToken(AppNotification appNotification) {
    String token = appNotification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      String errorMsg = String.format("FCM token not found for user: %s",
          appNotification.getUser().getUserId());
      throw new IllegalArgumentException(errorMsg);
    }
    
    return token;
  }

  // FCM 메시지 빌드
  private Message buildMessage(AppNotification appNotification, String token) {
    try {
      return Message.builder()
          .setToken(token)
          .setNotification(buildNotificationPayload(appNotification))
          .putAllData(buildDataPayload(appNotification))
          .build();
    } catch (Exception e) {
      log.error("Failed to build FCM message for notification: {}", appNotification.getId(), e);
      throw new CustomException(ErrorCode.INVALID_NOTIFICATION_DATA);
    }
  }

  // 알림 페이로드 구성
  private Notification buildNotificationPayload(AppNotification appNotification) {
    return Notification.builder()
        .setTitle(appNotification.getNotificationType().getType().name())
        .setBody(appNotification.getContent())
        .build();
  }

  // 데이터 페이로드 구성
  private Map<String, String> buildDataPayload(AppNotification appNotification) {
    Map<String, String> dataMap = new HashMap<>();
    dataMap.put("notificationId", appNotification.getId().toString());
    dataMap.put("type", appNotification.getNotificationType().getType().name());
    dataMap.put("content", appNotification.getContent());
    dataMap.put("createdAt", appNotification.getCreatedAt().toString());
    return dataMap;
  }

  private <T> List<List<T>> createBatches(List<T> list, int batchSize) {
    List<List<T>> batches = new ArrayList<>();
    for (int i = 0; i < list.size(); i += batchSize) {
      batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
    }
    return batches;
  }

  private MulticastMessage buildMulticastMessage(List<AppNotification> notifications) {
    List<String> tokens = notifications.stream()
            .map(this::validateAndGetToken)
            .toList();

    AppNotification firstNotification = notifications.get(0);
    
    return MulticastMessage.builder()
            .addAllTokens(tokens)
            .setNotification(buildNotificationPayload(firstNotification))
            .putAllData(buildDataPayload(firstNotification))
            .build();
  }

  private void handleBatchResponse(List<AppNotification> batch, BatchResponse response) {
    List<SendResponse> responses = response.getResponses();
    
    for (int i = 0; i < responses.size(); i++) {
      SendResponse sendResponse = responses.get(i);
      AppNotification notification = batch.get(i);
      
      if (sendResponse.isSuccessful()) {
        notification.markFcmSent();
      } else {
        log.warn("FCM send failed for notification: {}, error: {}", 
                notification.getId(), sendResponse.getException().getMessage());
      }
    }
  }

}