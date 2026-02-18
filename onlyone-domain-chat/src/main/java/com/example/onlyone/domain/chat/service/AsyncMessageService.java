package com.example.onlyone.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMessageService {

    private final MessageCommandService messageCommandService;
    private final StringRedisTemplate redisTemplate;

    private static final String FAILED_MESSAGES_KEY = "chat:failed-messages";
    private static final Duration FAILED_MESSAGES_TTL = Duration.ofDays(7);

    @Async("customAsyncExecutor")
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void saveMessageAsync(Long chatRoomId, Long userId, String text) {
        log.debug("[Async.SaveMessage] started: chatRoomId={}, userId={}", chatRoomId, userId);
        messageCommandService.saveMessage(chatRoomId, userId, text);
        log.info("[Async.SaveMessage] completed: chatRoomId={}, userId={}", chatRoomId, userId);
    }

    @Recover
    public void recover(Exception e, Long chatRoomId, Long userId, String text) {
        log.error("[Async.SaveMessage] final failure after retries: chatRoomId={}, userId={}, error={}",
                chatRoomId, userId, e.getMessage(), e);

        try {
            String failedEntry = String.join("|",
                    String.valueOf(chatRoomId),
                    String.valueOf(userId),
                    text.replace("|", "\\|"),
                    LocalDateTime.now().toString());
            redisTemplate.opsForList().rightPush(FAILED_MESSAGES_KEY, failedEntry);
            redisTemplate.expire(FAILED_MESSAGES_KEY, FAILED_MESSAGES_TTL);
            log.info("[Async.SaveMessage] failed message stored to Redis for retry: chatRoomId={}, userId={}",
                    chatRoomId, userId);
        } catch (Exception redisEx) {
            log.error("[Async.SaveMessage] Redis fallback also failed: chatRoomId={}, userId={}",
                    chatRoomId, userId, redisEx);
        }
    }
}
