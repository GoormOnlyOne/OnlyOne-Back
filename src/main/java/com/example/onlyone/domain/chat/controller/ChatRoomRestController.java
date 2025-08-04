package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.service.ChatRoomService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/chat")
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    @Operation(summary = "클럽 내 사용자의 채팅방 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<ChatRoomResponse>>> getUserChatRooms(@PathVariable Long clubId) {
        List<ChatRoomResponse> response = chatRoomService.getChatRoomsUserJoinedInClub(clubId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(summary = "채팅방 단건 상세 조회")
    @GetMapping("/{chatRoomId}")
    public ResponseEntity<CommonResponse<ChatRoomResponse>> getChatRoom(@PathVariable Long clubId, @PathVariable Long chatRoomId) {
        ChatRoomResponse response = chatRoomService.getById(chatRoomId, clubId); // clubId 포함 검증
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

