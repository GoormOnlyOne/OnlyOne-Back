package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Slf4j
@RequiredArgsConstructor
@Controller
public class ChatWebSocketController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 클라이언트 → 서버 메시지 전송
     * STOMP SEND to /pub/clubs/{clubId}/chatrooms/{chatRoomId}/messages
     */
    @MessageMapping("/clubs/{clubId}/chatrooms/{chatRoomId}/messages")
    public void handleMessage(
            @DestinationVariable Long clubId,
            @DestinationVariable Long chatRoomId,
            @DestinationVariable Long userId,
            @Payload ChatMessageRequest request
    ) {
        log.info("STOMP: userId={}, text={}", userId, request.getText());

        // 메시지 저장
        Message savedMessage = messageService.saveMessage(
                chatRoomId,
                userId,
                request.getText()
        );

        // 응답 객체 생성
        ChatMessageResponse response = (ChatMessageResponse) ChatMessageResponse.from(savedMessage);

        // /sub/clubs/{clubId}/chatrooms/{chatRoomId}/messages 구독자에게 메시지 브로드캐스트
        String destination = String.format("/sub/clubs/%d/chatrooms/%d/messages", clubId, chatRoomId);
        messagingTemplate.convertAndSend(destination, response);
    }
}
