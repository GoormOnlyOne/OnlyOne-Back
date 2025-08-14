package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
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

/**
 * FCM 서비스 - Firebase Cloud Messaging 푸시 알림 전송 및 토큰 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService {

  @Value("${app.notification.fcm.batch-size:500}")
  private int batchSize;
  
  @Value("${app.notification.fcm.max-retry:3}")
  private int maxRetry;

  private final FirebaseMessaging firebaseMessaging;
  private final NotificationRepository notificationRepository;
  private final PriorityBlockingQueue<FcmNotificationTask> priorityQueue = new PriorityBlockingQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

  /**
   * FCM 알림 전송 - 모든 예외를 CustomException으로 변환하여 글로벌 예외 처리
   */
  public void sendFcmNotification(AppNotification appNotification) {
    try {
      String token = validateAndGetToken(appNotification); // FCM 토큰 검증
      Message message = buildMessage(appNotification, token); // 메시지 구성

      String response = firebaseMessaging.send(message); // Firebase로 전송
      log.info("FCM sent successfully: response={}, notificationId={}",
          response, appNotification.getId());

    } catch (IllegalArgumentException e) {
      // FCM 토큰 관련 예외
      throw new CustomException(ErrorCode.FCM_TOKEN_NOT_FOUND);

    } catch (FirebaseMessagingException e) {
      // Firebase 서비스 예외
      
      // 특정 에러 코드에 따른 적절한 에러 반환
      if (isInvalidTokenError(e)) {
        throw new CustomException(ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
      }
      
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);

    } catch (Exception e) {
      // 기타 예상치 못한 예외
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);
    }
  }
  
  /**
   * Firebase 예외가 무효한 토큰 에러인지 확인 - 토큰 갱신 필요 여부 판단
   */
  private boolean isInvalidTokenError(FirebaseMessagingException e) {
    if (e.getErrorCode() == null) return false;
    
    String errorCode = e.getErrorCode().toString();
    return "INVALID_ARGUMENT".equals(errorCode) || 
           "UNREGISTERED".equals(errorCode) ||
           "INVALID_REGISTRATION".equals(errorCode);
  }

  /**
   * 우선순위 큐에 FCM 작업 추가
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
          log.error("Batch FCM send failed: batchSize={}", batch.size(), e);
          failureCount.addAndGet(batch.size());
        }
      });

      return BatchSendResult.of(successCount.get(), failureCount.get());
    });
  }

  /**
   * 실패한 FCM 알림 재전송
   */
  @Async
  @Transactional
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
      log.error("FCM retry failed for user: {}", userId, e);
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  // FCM 토큰 검증 및 추출
  private String validateAndGetToken(AppNotification appNotification) {
    String token = appNotification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      String errorMsg = String.format("FCM token not found for user: %s",
          appNotification.getUser().getUserId());
      throw new IllegalArgumentException(errorMsg);
    }
    
    return token;
  }

  // FCM 메시지 빌드 - 알림 페이로드와 데이터 페이로드 구성
  private Message buildMessage(AppNotification appNotification, String token) {
    try {
      return Message.builder()
          .setToken(token)
          .setNotification(buildNotificationPayload(appNotification)) // 알림 표시용
          .putAllData(buildDataPayload(appNotification)) // 앱 내 처리용 데이터
          .build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to build FCM message", e);
    }
  }

  // 알림 페이로드 구성 - 시스템 알림창에 표시될 내용
  private Notification buildNotificationPayload(AppNotification appNotification) {
    return com.google.firebase.messaging.Notification.builder()
        .setTitle(appNotification.getNotificationType().getType().name())
        .setBody(appNotification.getContent())
        .build();
  }

  // 데이터 페이로드 구성 - 앱에서 처리할 추가 정보
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

  /**
   * FCM 우선순위
   */
  public enum FcmPriority {
    HIGH(1),
    NORMAL(2),
    LOW(3);

    private final int value;

    FcmPriority(int value) {
      this.value = value;
    }

    public int getValue() { return value; }
  }

  /**
   * FCM 알림 작업
   */
  public static class FcmNotificationTask implements Comparable<FcmNotificationTask> {
    private final AppNotification notification;
    private final FcmPriority priority;
    private final long timestamp;

    private FcmNotificationTask(AppNotification notification, FcmPriority priority) {
      this.notification = Objects.requireNonNull(notification);
      this.priority = Objects.requireNonNull(priority);
      this.timestamp = System.currentTimeMillis();
    }

    public static FcmNotificationTask of(AppNotification notification, FcmPriority priority) {
      return new FcmNotificationTask(notification, priority);
    }

    @Override
    public int compareTo(FcmNotificationTask other) {
      int priorityComparison = Integer.compare(this.priority.getValue(), other.priority.getValue());
      if (priorityComparison != 0) {
        return priorityComparison;
      }
      return Long.compare(this.timestamp, other.timestamp);
    }

    public AppNotification getNotification() { return notification; }
    public FcmPriority getPriority() { return priority; }
    public long getTimestamp() { return timestamp; }
  }

  /**
   * 배치 전송 결과
   */
  public static class BatchSendResult {
    private final long successCount;
    private final long failureCount;

    private BatchSendResult(long successCount, long failureCount) {
      this.successCount = successCount;
      this.failureCount = failureCount;
    }

    public static BatchSendResult of(long successCount, long failureCount) {
      return new BatchSendResult(successCount, failureCount);
    }

    public static BatchSendResult empty() {
      return new BatchSendResult(0, 0);
    }

    public long getSuccessCount() { return successCount; }
    public long getFailureCount() { return failureCount; }
    public long getTotalCount() { return successCount + failureCount; }
  }
}