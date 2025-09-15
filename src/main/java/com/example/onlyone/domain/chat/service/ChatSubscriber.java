package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatMessageResponse dto = objectMapper.readValue(body, ChatMessageResponse.class);

            // Redis → WebSocket 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/sub/chat/" + dto.getChatRoomId() + "/messages",
                    dto
            );

            log.info("📡 메시지 브로드캐스트 완료 - roomId={}, text={}",
                    dto.getChatRoomId(), dto.getText());
        } catch (Exception e) {
            log.error("❌ Redis 메시지 처리 실패", e);
        }
    }
}
