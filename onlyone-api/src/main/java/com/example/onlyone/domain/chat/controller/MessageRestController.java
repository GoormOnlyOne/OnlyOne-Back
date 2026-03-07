package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.service.MessageCommandService;
import com.example.onlyone.domain.chat.service.MessageQueryService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class MessageRestController {

    private final MessageCommandService messageCommandService;
    private final MessageQueryService messageQueryService;
    private final UserService userService;

    @Operation(summary = "채팅 메시지 저장 (전송)")
    @PostMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<ChatMessageResponse>> sendMessage(
            @PathVariable Long chatRoomId,
            @RequestBody ChatMessageRequest request) {
        User user = userService.getCurrentUser();
        ChatMessageResponse response =
                messageCommandService.sendAndPublish(chatRoomId, user.getUserId(), request.text());
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "채팅 메시지 삭제")
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId) {
        User user = userService.getCurrentUser();
        messageCommandService.deleteMessage(messageId, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "채팅방 메시지 조회(최신이 아래, 커서 기반 페이지네이션)")
    @GetMapping("/{chatRoomId}/messages")
    public ResponseEntity<CommonResponse<ChatRoomMessageResponse>> getChatRoomMessages(
            @PathVariable Long chatRoomId,
            @RequestParam(required = false, defaultValue = "50") Integer size,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorAt) {
        ChatRoomMessageResponse response =
                messageQueryService.getChatRoomMessages(chatRoomId, size, cursorId, cursorAt);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}
