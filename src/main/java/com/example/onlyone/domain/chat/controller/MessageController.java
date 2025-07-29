package com.example.onlyone.domain.chat.controller;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    //메시지 전송
    @PostMapping
    public ResponseEntity<Message> sendMessage(@RequestBody MessageRequestDto dto) {
        Message message = messageService.saveMessage(
                dto.getChatRoomId(),
                dto.getUserId(),
                dto.getText(),
                dto.getSentAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    //메시지 삭제
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId,
                                              @RequestParam Long userId) {
        messageService.deleteMessage(messageId, userId);
        return ResponseEntity.noContent().build();
    }

    //메시지 목록 조회
    @GetMapping("/room/{chatRoomId}")
    public ResponseEntity<List<Message>> getMessages(@PathVariable Long chatRoomId) {
        return ResponseEntity.ok(messageService.getMessages(chatRoomId));
    }
}

