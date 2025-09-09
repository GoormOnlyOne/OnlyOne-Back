package com.example.onlyone.global.sse.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SseMetrics {
    
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger pendingEvents = new AtomicInteger(0);
    private final AtomicLong totalBytesTransferred = new AtomicLong(0);
    
    private final Counter connectionCreatedCounter;
    private final Counter connectionClosedCounter;
    private final Counter connectionErrorCounter;
    private final Counter connectionTimeoutCounter;
    
    private final Counter eventSentCounter;
    private final Counter eventFailedCounter;
    private final Counter eventBatchedCounter;
    
    private final Counter notificationCreatedCounter;
    private final Counter notificationSentCounter;
    private final Counter notificationFailedCounter;
    private final Counter notificationMarkedReadCounter;
    private final Counter notificationDeletedCounter;
    
    private final Timer eventSendTimer;
    private final Timer notificationProcessingTimer;
    private final Timer missedNotificationRecoveryTimer;
    
    private final Counter rateLimitExceededCounter;
    private final Counter maxConnectionsReachedCounter;
    private final Counter connectionRejectedCounter;
    
    public SseMetrics(MeterRegistry registry) {
        Gauge.builder("sse.connections.active", activeConnections, AtomicInteger::get)
            .description("Currently active SSE connections")
            .register(registry);
            
        Gauge.builder("sse.events.pending", pendingEvents, AtomicInteger::get)
            .description("Events waiting in batch queue")
            .register(registry);
            
        Gauge.builder("sse.bytes.transferred", totalBytesTransferred, AtomicLong::get)
            .description("Total bytes transferred via SSE")
            .baseUnit("bytes")
            .register(registry);
        
        connectionCreatedCounter = Counter.builder("sse.connections.created")
            .description("Total SSE connections created")
            .register(registry);
            
        connectionClosedCounter = Counter.builder("sse.connections.closed")
            .description("Total SSE connections closed")
            .register(registry);
            
        connectionErrorCounter = Counter.builder("sse.connections.errors")
            .description("Total SSE connection errors")
            .tag("type", "error")
            .register(registry);
            
        connectionTimeoutCounter = Counter.builder("sse.connections.timeouts")
            .description("Total SSE connection timeouts")
            .tag("type", "timeout")
            .register(registry);
        
        eventSentCounter = Counter.builder("sse.events.sent")
            .description("Total SSE events sent successfully")
            .register(registry);
            
        eventFailedCounter = Counter.builder("sse.events.failed")
            .description("Total SSE events failed to send")
            .register(registry);
            
        eventBatchedCounter = Counter.builder("sse.events.batched")
            .description("Total SSE events processed in batch")
            .register(registry);
        
        notificationCreatedCounter = Counter.builder("notifications.created")
            .description("Total notifications created")
            .register(registry);
            
        notificationSentCounter = Counter.builder("notifications.sent")
            .description("Total notifications sent via SSE")
            .register(registry);
            
        notificationFailedCounter = Counter.builder("notifications.failed")
            .description("Total notifications failed to send")
            .register(registry);
            
        notificationMarkedReadCounter = Counter.builder("notifications.marked_read")
            .description("Total notifications marked as read")
            .register(registry);
            
        notificationDeletedCounter = Counter.builder("notifications.deleted")
            .description("Total notifications deleted")
            .register(registry);
        
        eventSendTimer = Timer.builder("sse.event.send.duration")
            .description("Time taken to send SSE event")
            .register(registry);
            
        notificationProcessingTimer = Timer.builder("notification.processing.duration")
            .description("Time taken to process notification")
            .register(registry);
            
        missedNotificationRecoveryTimer = Timer.builder("sse.missed.recovery.duration")
            .description("Time taken to recover missed notifications")
            .register(registry);
        
        rateLimitExceededCounter = Counter.builder("sse.rate_limit.exceeded")
            .description("Number of times rate limit was exceeded")
            .register(registry);
            
        maxConnectionsReachedCounter = Counter.builder("sse.max_connections.reached")
            .description("Number of times max connections limit was reached")
            .register(registry);
            
        connectionRejectedCounter = Counter.builder("sse.connections.rejected")
            .description("Number of connections rejected")
            .register(registry);
    }
    
    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
        connectionCreatedCounter.increment();
    }
    
    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
        connectionClosedCounter.increment();
    }
    
    public void recordConnectionError() {
        connectionErrorCounter.increment();
    }
    
    public void recordConnectionTimeout() {
        connectionTimeoutCounter.increment();
    }
    
    public void recordEventSent() {
        eventSentCounter.increment();
    }
    
    public void recordEventFailed() {
        eventFailedCounter.increment();
    }
    
    public void recordEventBatched() {
        eventBatchedCounter.increment();
    }
    
    public void recordNotificationCreated() {
        notificationCreatedCounter.increment();
    }
    
    public void recordNotificationSent() {
        notificationSentCounter.increment();
    }
    
    public void recordNotificationFailed() {
        notificationFailedCounter.increment();
    }
    
    public void recordNotificationMarkedRead() {
        notificationMarkedReadCounter.increment();
    }
    
    public void recordNotificationDeleted() {
        notificationDeletedCounter.increment();
    }
    
    public void recordRateLimitExceeded() {
        rateLimitExceededCounter.increment();
    }
    
    public void recordMaxConnectionsReached() {
        maxConnectionsReachedCounter.increment();
    }
    
    public void recordConnectionRejected() {
        connectionRejectedCounter.increment();
    }
    
    public void updatePendingEvents(int count) {
        pendingEvents.set(count);
    }
    
    public void addBytesTransferred(long bytes) {
        totalBytesTransferred.addAndGet(bytes);
    }
    
    public Timer.Sample startEventSendTimer() {
        return Timer.start();
    }
    
    public void stopEventSendTimer(Timer.Sample sample) {
        sample.stop(eventSendTimer);
    }
    
    public Timer.Sample startNotificationProcessingTimer() {
        return Timer.start();
    }
    
    public void stopNotificationProcessingTimer(Timer.Sample sample) {
        sample.stop(notificationProcessingTimer);
    }
    
    public Timer.Sample startMissedRecoveryTimer() {
        return Timer.start();
    }
    
    public void stopMissedRecoveryTimer(Timer.Sample sample) {
        sample.stop(missedNotificationRecoveryTimer);
    }
    
    public int getActiveConnectionCount() {
        return activeConnections.get();
    }
}