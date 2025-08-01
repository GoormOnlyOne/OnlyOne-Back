package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
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
     * 2. 구독 중인 클라이언트에게 메시지 전송
     */
    @MessageMapping("/chat/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageRequest request) {

        System.out.println("🔥 WebSocket 메시지 수신: userId=" + request.getUserId() + ", text=" + request.getText());

        try {
            // 1. 메시지 저장
            ChatMessageResponse response = messageService.saveMessage(chatRoomId, request.getUserId(), request.getText());
            System.out.println("✅ 메시지 저장 완료, 전송 준비: " + response.getText());

            // 2. 대상 경로 구성 및 전송
            String destination = "/sub/chat/" + chatRoomId + "/messages";
            messagingTemplate.convertAndSend(destination, response);

        } catch (CustomException e) {
            System.out.println("❌ CustomException: " + e.getMessage());
            throw e; // -> @MessageExceptionHandler 로 위임
        } catch (Exception e) {
            System.out.println("❌ 처리 중 알 수 없는 예외 발생: " + e.getMessage());
            throw new CustomException(ErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    /**
     * WebSocket 메시지 처리 중 예외 발생 시 클라이언트에게 전송
     */
    @MessageExceptionHandler(CustomException.class)
    @SendToUser("/queue/errors")
    public String handleCustomException(CustomException ex) {
        return ex.getErrorCode().getMessage();
    }

}