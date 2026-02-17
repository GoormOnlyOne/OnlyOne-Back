package com.example.onlyone.sse.service;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.sse.SseConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SseEventSender {

    private final SseConnectionManager connectionManager;
    private final Executor sseEventExecutor;
    private final AtomicLong eventIdCounter = new AtomicLong(0);

    public SseEventSender(
            SseConnectionManager connectionManager,
            @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.connectionManager = connectionManager;
        this.sseEventExecutor = sseEventExecutor;
    }

    private static final int MAX_DATA_SIZE = 64 * 1024;
    private static final Set<String> CLIENT_DISCONNECT_MESSAGES = Set.of(
            "Broken pipe",
            "Connection reset by peer", 
            "An existing connection was forcibly closed"
    );

    public boolean isUserConnected(Long userId) {
        return connectionManager.isUserConnected(userId);
    }

    public CompletableFuture<Boolean> sendEvent(Long userId, String eventName, Object data) {
        return Optional.ofNullable(connectionManager.getConnection(userId))
                .map(connection -> CompletableFuture.supplyAsync(() ->
                        sendEventInternal(connection, userId, eventName, data), sseEventExecutor))
                .orElse(CompletableFuture.completedFuture(false));
    }

    private boolean sendEventInternal(SseConnection connection, Long userId, String eventName, Object data) {
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

            return true;
        } catch (IOException e) {
            handleIOException(e, userId, eventName);
            return false;
        } catch (IllegalStateException e) {
            connectionManager.cleanupConnection(userId);
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        } catch (Exception e) {
            connectionManager.cleanupConnection(userId);
            throw new CustomException(ErrorCode.SSE_SEND_FAILED);
        }
    }

    private void handleIOException(IOException e, Long userId, String eventName) {
        String errorMessage = e.getMessage();
        boolean isClientDisconnect = errorMessage != null &&
                CLIENT_DISCONNECT_MESSAGES.stream().anyMatch(errorMessage::contains);

        if (!isClientDisconnect) {
            log.error("Failed to send SSE event: userId={}, eventName={}", userId, eventName, e);
            throw new CustomException(ErrorCode.SSE_SEND_FAILED);
        }

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