package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.domain.chat.service.ChatPublisher;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class MessageRestController {

    private final MessageService messageService;
    private final UserService userService;
    private final ChatPublisher chatPublisher;
    private final ObjectMapper objectMapper; // JSON 직렬화용

    @Operation(summary = "채팅 메시지 저장 (전송)")
    @PostMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<ChatMessageResponse>> sendMessage(
            @PathVariable Long chatRoomId,
            @RequestBody ChatMessageRequest request
    ) throws JsonProcessingException {
        // 1. 메시지 DB 저장
        ChatMessageResponse response =
                messageService.saveMessage(chatRoomId, request.userId(), request.text());

        // 2. Redis Pub/Sub 발행 (JSON 직렬화)
        String payload = objectMapper.writeValueAsString(response);
        chatPublisher.publish(chatRoomId, payload);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "채팅 메시지 삭제")
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId) {
        User user = userService.getCurrentUser(); // kakaoId 기반 조회
        messageService.deleteMessage(messageId, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "채팅방 메시지 조회(최신이 아래, 커서 기반 페이지네이션)")
    @GetMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<ChatRoomMessageResponse>> getChatRoomMessages(
            @PathVariable Long chatRoomId,
            @RequestParam(required = false, defaultValue = "50") Integer size,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorAt
    ) {
        ChatRoomMessageResponse response =
                messageService.getChatRoomMessages(chatRoomId, size, cursorId, cursorAt);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}