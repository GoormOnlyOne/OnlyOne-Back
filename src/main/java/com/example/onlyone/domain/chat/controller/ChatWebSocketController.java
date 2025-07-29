package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.service.MessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatWebSocketController {

    private final MessageService messageService;

    public ChatWebSocketController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 클라이언트가 /pub/clubs/{clubId}/chatrooms/{chatRoomId}/messages 로 메시지를 보내면
     * service에서 저장 후 /sub/clubs/{clubId}/chatrooms/{chatRoomId}/messages 로 브로드캐스트
     */
    @MessageMapping("/pub/clubs/{clubId}/chatrooms/{chatRoomId}/messages")
    @SendTo("/sub/clubs/{clubId}/chatrooms/{chatRoomId}/messages")
    public MessageResponse publishMessage(
            @DestinationVariable Long clubId,
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageWebSocketRequest req) {

        // 실제 저장 로직과 권한/파라미터 체크는 service에 위임
        // Service 메소드 시그니처는 (clubId, chatRoomId, userId, text) 형태로 만드세요.
        var saved = messageService.sendMessage(clubId, chatRoomId, req.getUserId(), req.getText());

        // MessageService.sendMessage(...)는 내부적으로 MessageResponse(엔티티 기반 DTO)를 리턴한다고 가정
        ChatMessageWebSocketResponse res = new ChatMessageWebSocketResponse();
        res.setMessageId(saved.getMessageId());
        res.setChatId(saved.getRoomId());       // chatRoomId
        res.setUserId(saved.getUserId());
        res.setText(saved.getText());
        res.setSendAt(saved.getSentAt());
        res.setDeleted(saved.getDeleted());

        return res;
    }

    /**
     * WebSocket 전용 예외 처리 (optional)
     * 예외가 발생하면 호출한 유저에게 /user/queue/errors 로 에러 페이로드 전송
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public String handleError(Exception ex) {
        // DTO로 만들어도 되고, 단순 문자열 메시지도 가능합니다.
        return ex.getMessage();
    }
}
