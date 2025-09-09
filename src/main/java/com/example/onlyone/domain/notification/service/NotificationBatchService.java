package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationPriority;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 배치 알림 처리 서비스
 * 대량 알림을 효율적으로 처리
 */
@Service
@Slf4j
public class NotificationBatchService {
    
    private final NotificationCreationService notificationCreationService;
    private final Map<NotificationPriority, Queue<BatchNotification>> priorityQueues = new EnumMap<>(NotificationPriority.class);
    private final ScheduledExecutorService batchProcessor = Executors.newScheduledThreadPool(4);
    private final AtomicInteger currentBatchSize = new AtomicInteger(500);
    
    @Value("${notification.batch.size:500}")
    private int defaultBatchSize;
    
    @Value("${notification.batch.interval:100}")
    private int batchIntervalMs;
    
    public NotificationBatchService(NotificationCreationService notificationCreationService) {
        this.notificationCreationService = notificationCreationService;
        // 우선순위별 큐 초기화
        for (NotificationPriority priority : NotificationPriority.values()) {
            priorityQueues.put(priority, new ConcurrentLinkedQueue<>());
        }
        startBatchProcessing();
    }
    
    /**
     * 대량 알림 전송 (배치 처리)
     */
    @Async("notificationExecutor")
    public CompletableFuture<Integer> sendBatchNotifications(
            List<User> users, 
            Type type, 
            NotificationPriority priority,
            String... args) {
        
        // 적응형 배치 크기 사용
        int batchSize = currentBatchSize.get();
        List<List<User>> batches = partition(users, batchSize);
        
        log.info("대량 알림 처리 시작: {} 사용자, {} 배치 (우선순위: {})", 
                users.size(), batches.size(), priority);
        
        // 배치를 우선순위 큐에 추가
        for (List<User> batch : batches) {
            BatchNotification batchNotification = new BatchNotification(batch, type, priority, args);
            priorityQueues.get(priority).offer(batchNotification);
        }
        
        return CompletableFuture.completedFuture(users.size());
    }
    
    /**
     * 우선순위 기반 배치 처리
     */
    private void startBatchProcessing() {
        batchProcessor.scheduleWithFixedDelay(() -> {
            try {
                processBatches();
            } catch (Exception e) {
                log.error("배치 처리 중 오류", e);
            }
        }, 0, batchIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    private void processBatches() {
        // 우선순위 순서대로 처리
        for (NotificationPriority priority : NotificationPriority.values()) {
            Queue<BatchNotification> queue = priorityQueues.get(priority);
            
            // 해당 우선순위의 배치 처리
            int processed = 0;
            BatchNotification batch;
            while ((batch = queue.poll()) != null && processed < 10) {
                processSingleBatch(batch);
                processed++;
            }
        }
    }
    
    private void processSingleBatch(BatchNotification batch) {
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failureCount = 0;
        
        // 병렬 스트림으로 빠른 처리
        List<CompletableFuture<Notification>> futures = batch.getUsers().parallelStream()
            .map(user -> notificationCreationService.createNotificationOptimized(user, batch.getType(), batch.getArgs()))
            .collect(Collectors.toList());
        
        // 모든 Future 완료 대기
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        try {
            allFutures.get(5, TimeUnit.SECONDS); // 5초 타임아웃
            successCount = futures.size();
        } catch (Exception e) {
            log.warn("배치 처리 타임아웃 또는 오류: {}", e.getMessage());
            failureCount = (int) futures.stream().filter(f -> !f.isDone()).count();
            successCount = futures.size() - failureCount;
        }
        
        long duration = System.currentTimeMillis() - startTime;
        
        // 적응형 배치 크기 조정
        adjustBatchSize(successCount, failureCount, duration);
        
        log.debug("배치 처리 완료: {} 사용자, {}ms 소요, 성공: {}, 실패: {}", 
                batch.getUsers().size(), duration, successCount, failureCount);
    }
    
    /**
     * 성능 기반 배치 크기 자동 조정
     */
    private void adjustBatchSize(int successCount, int failureCount, long duration) {
        double successRate = successCount / (double)(successCount + failureCount);
        
        if (successRate > 0.95 && duration < 1000 && currentBatchSize.get() < 2000) {
            // 성공률 높고 빠름 - 배치 크기 증가
            currentBatchSize.addAndGet(100);
            log.debug("배치 크기 증가: {}", currentBatchSize.get());
        } else if (successRate < 0.85 || duration > 3000) {
            // 성공률 낮거나 느림 - 배치 크기 감소
            currentBatchSize.updateAndGet(size -> Math.max(100, size - 50));
            log.debug("배치 크기 감소: {}", currentBatchSize.get());
        }
    }
    
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
    
    private static class BatchNotification {
        private final List<User> users;
        private final Type type;
        private final NotificationPriority priority;
        private final String[] args;
        
        public BatchNotification(List<User> users, Type type, NotificationPriority priority, String[] args) {
            this.users = users;
            this.type = type;
            this.priority = priority;
            this.args = args;
        }
        
        public List<User> getUsers() { return users; }
        public Type getType() { return type; }
        public NotificationPriority getPriority() { return priority; }
        public String[] getArgs() { return args; }
    }
}