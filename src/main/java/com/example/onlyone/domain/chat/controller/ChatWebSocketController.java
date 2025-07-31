package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 클라이언트가 메시지를 전송하면:
     * 1. 메시지를 DB에 저장
     * 2. 클라이언트에게 전송
     */
    @MessageMapping("pub/clubs/{clubId}/chatrooms/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long clubId,
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageRequest request) {

        System.out.println("🔥 sendMessage 진입: userId=" + request.getUserId() + ", text=" + request.getText());

        /*
        try {
            // 1. 저장
            Message saved = messageService.saveMessage(chatRoomId, request.getUserId(), request.getText());
            System.out.println("✅ 저장 완료");

            // 2. 응답 생성
            ChatMessageResponse response = ChatMessageResponse.from(saved);
            System.out.println("📦 응답 DTO 생성 완료: " + response.getText());

            // 3. 브로커로 전송
            String destination = "/sub/clubs/" + clubId + "/chatrooms/" + chatRoomId + "/messages";
            messagingTemplate.convertAndSend(destination, response);
            System.out.println("✅ 전송 완료: " + destination);

        } catch (Exception e) {
            System.out.println("❌ 예외 발생: " + e.getMessage());
            e.printStackTrace();
        }
        */
    }

    /**
     * WebSocket 메시지 처리 중 발생하는 예외를 클라이언트에게 전달
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public String handleException(Exception ex) {
        System.out.println("❗ WebSocket 처리 중 예외 발생: " + ex.getMessage());
        return ex.getMessage();
    }

    @MessageMapping("/test")
    @SendTo("/sub/test")
    public String test(String msg) {
        System.out.println("🔥🔥 /test 진입: " + msg);
        return "Echo: " + msg;
    }
}