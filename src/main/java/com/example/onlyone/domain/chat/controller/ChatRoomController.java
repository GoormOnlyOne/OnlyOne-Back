package com.example.onlyone.domain.chat.controller;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    //채팅방 생성
    @PostMapping("/rooms")
    public ResponseEntity<ChatRoom> createChatRoom(@RequestParam Long clubId,
                                                   @RequestParam(required = false) Long scheduleId,
                                                   @RequestParam Type type) {
        ChatRoom chatRoom = chatRoomService.createChatRoom(clubId, scheduleId, type);
        return ResponseEntity.status(HttpStatus.CREATED).body(chatRoom);
    }

    //채팅방 삭제
    @DeleteMapping("/rooms/{chatRoomId}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long chatRoomId) {
        chatRoomService.deleteChatRoom(chatRoomId);
        return ResponseEntity.noContent().build();
    }

    //채팅방 목록 조회 (
    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoom>> getChatRoomsByClub(@RequestParam Long clubId) {
        return ResponseEntity.ok(chatRoomService.getChatRoomsByClub(clubId));
    }

    @GetMapping("/rooms/{chatRoomId}/users")
    public ResponseEntity<List<UserChatRoom>> getChatRoomUsers(@PathVariable Long chatRoomId) {
        return ResponseEntity.ok(chatRoomService.getChatRoomUsers(chatRoomId));
    }

    @GetMapping("/rooms/{chatRoomId}/users/{userId}/exists")
    public ResponseEntity<Boolean> isUserInChatRoom(@PathVariable Long chatRoomId, @PathVariable Long userId) {
        return ResponseEntity.ok(chatRoomService.isUserInChatRoom(userId, chatRoomId));
    }
}