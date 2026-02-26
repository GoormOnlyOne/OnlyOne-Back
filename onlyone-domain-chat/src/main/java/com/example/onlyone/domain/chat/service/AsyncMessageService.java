package com.example.onlyone.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMessageService {

    private final MessageCommandService messageCommandService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String FAILED_MESSAGES_KEY = "chat:failed-messages";
    private static final Duration FAILED_MESSAGES_TTL = Duration.ofDays(7);

    @Async("customAsyncExecutor")
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 3, maxDelay = 2000)
    )
    public void saveMessageAsync(Long chatRoomId, Long userId, String text) {
        messageCommandService.saveMessage(chatRoomId, userId, text);
    }

    @Recover
    public void recover(Exception e, Long chatRoomId, Long userId, String text) {
        log.error("메시지 비동기 저장 최종 실패: chatRoomId={}, userId={}", chatRoomId, userId, e);

        try {
            String failedEntry = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", chatRoomId,
                    "userId", userId,
                    "text", text,
                    "failedAt", LocalDateTime.now().toString()));
            redisTemplate.opsForList().rightPush(FAILED_MESSAGES_KEY, failedEntry);
            redisTemplate.expire(FAILED_MESSAGES_KEY, FAILED_MESSAGES_TTL);
            log.info("실패 메시지 Redis 저장 완료: chatRoomId={}, userId={}", chatRoomId, userId);
        } catch (JsonProcessingException jsonEx) {
            log.error("실패 메시지 직렬화 실패: chatRoomId={}, userId={}", chatRoomId, userId, jsonEx);
        } catch (Exception redisEx) {
            log.error("Redis 폴백 저장 실패: chatRoomId={}, userId={}", chatRoomId, userId, redisEx);
        }
    }
}
