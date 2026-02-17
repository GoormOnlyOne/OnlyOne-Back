package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMessageService {

    private final MessageService messageService;

    @Async("customAsyncExecutor")   // Bean 이름 맞춰줌!
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void saveMessageAsync(Long chatRoomId, ChatMessageRequest request) {
        log.debug("[Async.SaveMessage] started: chatRoomId={}, userId={}", chatRoomId, request.userId());
        messageService.saveMessage(chatRoomId, request.userId(), request.text());
        log.info("[Async.SaveMessage] completed: chatRoomId={}, userId={}", chatRoomId, request.userId());
    }

    @Recover
    public void recover(Exception e, Long chatRoomId, ChatMessageRequest request) {
        log.error("[Async.SaveMessage] final failure after retries: chatRoomId={}, userId={}, error={}",
                chatRoomId, request.userId(), e.getMessage(), e);
        // TODO: 실패 메시지 Redis 등에 임시 저장 → 배치로 재처리
    }
}