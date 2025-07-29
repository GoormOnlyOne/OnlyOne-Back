package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.service.UserChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chatrooms/{chatRoomId}/users")
public class UserChatRoomController {

    private final UserChatRoomService userChatRoomService;

    // 채팅방 참여
    @PostMapping
    public ResponseEntity<Void> joinChatRoom(
            @PathVariable Long chatRoomId,
            @RequestParam Long userId,
            @RequestParam ChatRole chatRole
    ) {
        userChatRoomService.joinChatRoom(userId, chatRoomId, chatRole);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 채팅방 나가기
    @DeleteMapping
    public ResponseEntity<Void> leaveChatRoom(
            @PathVariable Long chatRoomId,
            @RequestParam Long userId
    ) {
        userChatRoomService.leaveChatRoom(userId, chatRoomId);
        return ResponseEntity.noContent().build();
    }
}