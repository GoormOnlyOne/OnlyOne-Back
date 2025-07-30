package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.service.ChatRoomService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static java.util.stream.Collectors.toList;

@RestController
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/chatrooms")
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    /*
    // 채팅방 삭제
    @DeleteMapping("/{chatRoomId}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long clubId, @PathVariable Long chatRoomId) {
        chatRoomService.deleteChatRoom(chatRoomId, clubId);
        return ResponseEntity.noContent().build();
    }
    */

    // 유저가 해당 클럽에서 참여 중인 채팅방 목록 조회
    @Operation(summary = "클럽 내 사용자의 채팅방 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<ChatRoomResponse>>> getUserChatRooms(
            @Parameter(description = "클럽 ID", example = "1") @PathVariable Long clubId,
            @Parameter(description = "사용자 ID", example = "1") @RequestParam Long userId
    ) {
        List<ChatRoom> chatRooms = chatRoomService.getChatRoomsUserJoinedInClub(userId, clubId);

        List<ChatRoomResponse> response = chatRooms.stream()
                .map(ChatRoomResponse::from)
                .collect(toList());

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}
