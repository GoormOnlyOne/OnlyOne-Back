package com.example.onlyone.global.reactive;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component("chatMessageSubscriber")
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "reactive")
@RequiredArgsConstructor
public class ReactiveChatSubscriber implements MessageListener {

    private final ReactiveChatConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatMessageResponse dto = objectMapper.readValue(body, ChatMessageResponse.class);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "message");
            payload.put("chatRoomId", dto.chatRoomId());
            payload.put("messageId", dto.messageId());
            payload.put("senderId", dto.senderId());
            payload.put("senderNickname", dto.senderNickname());
            payload.put("text", dto.text());
            payload.put("sentAt", dto.sentAt() != null ? dto.sentAt().toString() : null);
            payload.put("imageUrl", dto.imageUrl());

            String json = objectMapper.writeValueAsString(payload);
            connectionManager.broadcast(dto.chatRoomId(), json);

            log.debug("Reactive 채팅 브로드캐스트: chatRoomId={}", dto.chatRoomId());
        } catch (Exception e) {
            log.error("Reactive 채팅 메시지 수신 처리 실패", e);
        }
    }
}
