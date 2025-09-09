package com.example.onlyone.global.sse.event;

import com.example.onlyone.global.sse.SseConnection;
import com.example.onlyone.global.sse.connection.SseConnectionManager;
import com.example.onlyone.global.sse.metrics.SseMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 이벤트 전송 전용 클래스
 */
@Slf4j
@Component
public class SseEventSender {

    private final SseConnectionManager connectionManager;
    private final SseMetrics sseMetrics;
    private final Executor sseEventExecutor;
    private final AtomicLong eventIdCounter = new AtomicLong(0);
    
    private static final int MAX_DATA_SIZE = 64 * 1024;
    private static final String BROKEN_PIPE_MSG = "Broken pipe";
    private static final String RESET_BY_PEER_MSG = "Connection reset by peer";
    private static final String WRITE_ERROR_MSG = "An existing connection was forcibly closed";

    public SseEventSender(SseConnectionManager connectionManager, 
                         SseMetrics sseMetrics, 
                         @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.connectionManager = connectionManager;
        this.sseMetrics = sseMetrics;
        this.sseEventExecutor = sseEventExecutor;
    }

    /**
     * SSE 이벤트 전송 (비동기)
     */
    public CompletableFuture<Boolean> sendEvent(Long userId, String eventName, Object data) {
        SseConnection connection = connectionManager.getConnection(userId);
        if (connection == null) {
            log.debug("No SSE connection found for user: {}", userId);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            return sendEventInternal(connection, userId, eventName, data);
        }, sseEventExecutor);
    }

    /**
     * SSE 이벤트 전송 (동기)
     */
    public boolean sendEventSync(Long userId, String eventName, Object data) {
        SseConnection connection = connectionManager.getConnection(userId);
        if (connection == null) {
            log.debug("No SSE connection found for user: {}", userId);
            return false;
        }

        return sendEventInternal(connection, userId, eventName, data);
    }
    
    private boolean sendEventInternal(SseConnection connection, Long userId, String eventName, Object data) {
        Timer.Sample sample = sseMetrics.startEventSendTimer();
        try {
            if (isDataTooLarge(data)) {
                log.warn("Event data too large: userId={}, eventName={}, truncating", userId, eventName);
                data = truncateData(data);
            }
            
            String eventId = "evt_" + System.currentTimeMillis() + "_" + eventIdCounter.incrementAndGet();
            
            connection.getEmitter().send(SseEmitter.event()
                .id(eventId)
                .name(eventName)
                .data(data));

            sseMetrics.stopEventSendTimer(sample);
            sseMetrics.recordEventSent();
            log.debug("SSE event sent: userId={}, eventName={}, eventId={}", userId, eventName, eventId);
            return true;
        } catch (IOException e) {
            handleIOException(e, userId, eventName, sample);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error while sending SSE event: userId={}, eventName={}", userId, eventName, e);
            sseMetrics.stopEventSendTimer(sample);
            sseMetrics.recordEventFailed();
            connectionManager.cleanupConnection(userId);
            return false;
        }
    }
    
    private void handleIOException(IOException e, Long userId, String eventName, Timer.Sample sample) {
        String errorMessage = e.getMessage();
        boolean isClientDisconnect = errorMessage != null && 
            (errorMessage.contains(BROKEN_PIPE_MSG) || 
             errorMessage.contains(RESET_BY_PEER_MSG) ||
             errorMessage.contains(WRITE_ERROR_MSG));
        
        if (isClientDisconnect) {
            log.debug("Client disconnected: userId={}, eventName={} ({})", userId, eventName, errorMessage);
        } else {
            log.error("Failed to send SSE event: userId={}, eventName={}", userId, eventName, e);
        }
        
        sseMetrics.stopEventSendTimer(sample);
        sseMetrics.recordEventFailed();
        connectionManager.cleanupConnection(userId);
    }
    
    private boolean isDataTooLarge(Object data) {
        if (data == null) return false;
        String dataStr = data.toString();
        return dataStr.length() * 2 > MAX_DATA_SIZE;
    }
    
    private Object truncateData(Object data) {
        if (data == null) return null;
        String dataStr = data.toString();
        int maxLength = MAX_DATA_SIZE / 2;
        if (dataStr.length() <= maxLength) return data;
        return dataStr.substring(0, maxLength - 3) + "...";
    }
}