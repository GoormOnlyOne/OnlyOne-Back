package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.service.ChatRoomQueryService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clubs/{clubId}/chat")
public class ChatRoomRestController {

    private final ChatRoomQueryService chatRoomQueryService;

    @Operation(summary = "클럽 내 사용자의 채팅방 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<ChatRoomResponse>>> getUserChatRooms(@PathVariable Long clubId) {
        List<ChatRoomResponse> response = chatRoomQueryService.getChatRoomsUserJoinedInClub(clubId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}
