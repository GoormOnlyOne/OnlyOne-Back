package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/chatrooms")
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    // 채팅방 삭제
    @DeleteMapping("/{chatRoomId}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long clubId, @PathVariable Long chatRoomId) {
        chatRoomService.deleteChatRoom(chatRoomId, clubId);
        return ResponseEntity.noContent().build();
    }

    // 유저가 해당 클럽에서 참여 중인 채팅방 목록 조회
    @GetMapping
    public ResponseEntity<List<ChatRoom>> getUserChatRooms(
            @PathVariable Long clubId,
            @RequestParam Long userId
    ) {
        List<ChatRoom> chatRooms = chatRoomService.getChatRoomsUserJoinedInClub(userId, clubId);
        return ResponseEntity.ok(chatRooms);
    }
}
