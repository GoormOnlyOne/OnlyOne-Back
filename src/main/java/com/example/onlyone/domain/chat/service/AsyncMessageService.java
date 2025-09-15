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
            value = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void saveMessageAsync(Long chatRoomId, ChatMessageRequest request) {
        log.debug("💾 [ASYNC] 메시지 저장 시작 (chatRoomId={}, userId={})", chatRoomId, request.getUserId());
        messageService.saveMessage(chatRoomId, request.getUserId(), request.getText());
        log.info("💾 [ASYNC] 메시지 저장 완료 (chatRoomId={}, userId={})", chatRoomId, request.getUserId());
    }

    @Recover
    public void recover(Exception e, Long chatRoomId, ChatMessageRequest request) {
        log.error("❌ [ASYNC-RECOVER] 메시지 저장 최종 실패 (chatRoomId={}, userId={}) - {}",
                chatRoomId, request.getUserId(), e.getMessage(), e);
        // TODO: 실패 메시지 Redis 등에 임시 저장 → 배치로 재처리
    }
}