package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/chatrooms/{chatRoomId}/messages")
public class MessageRestController {

    private final MessageService messageService;

    // 메시지 목록 조회
    @GetMapping
    public ResponseEntity<List<ChatMessageResponse>> getMessages(@PathVariable Long chatRoomId) {
        List<Message> messages = messageService.getMessages(chatRoomId);
        List<ChatMessageResponse> responses = messages.stream()
                .map(ChatMessageResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    // 메시지 삭제 (본인만 가능)
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long chatRoomId,
            @PathVariable Long messageId,
            @RequestParam Long userId
    ) {
        messageService.deleteMessage(messageId, userId);
        return ResponseEntity.noContent().build();
    }
}
