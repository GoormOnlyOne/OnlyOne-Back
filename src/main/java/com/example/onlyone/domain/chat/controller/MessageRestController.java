package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class MessageRestController {

    private final MessageService messageService;

    @Operation(summary = "채팅방 메시지 목록 조회")
    @GetMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<List<ChatMessageResponse>>> getMessages(@PathVariable Long chatRoomId) {
        List<ChatMessageResponse> response = messageService.getMessages(chatRoomId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "채팅 메시지 저장 (전송)")
    @PostMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<ChatMessageResponse>> sendMessage(
            @PathVariable Long chatRoomId,
            @RequestBody ChatMessageRequest request
    ) {
        ChatMessageResponse response = messageService.saveMessage(chatRoomId, request.getUserId(), request.getText());
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "채팅 메시지 삭제")
    @DeleteMapping("/messages/{messageId}/delete")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long messageId,
            @RequestHeader("X-USER-ID") Long userId
    ) {
        messageService.deleteMessage(messageId, userId);
        return ResponseEntity.noContent().build();
    }

}