package com.example.onlyone.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPublisher {

    private final StringRedisTemplate redisTemplate;

    /**
     * 채팅방 ID 기반으로 Redis Pub/Sub 채널에 메시지를 발행
     *
     * @param roomId  채팅방 ID
     * @param message 발행할 메시지 (JSON or Text)
     */
    public void publish(Long roomId, String message) {
        if (roomId == null || message == null || message.isBlank()) return;
        String channel = "chat.room." + roomId;
        redisTemplate.convertAndSend(channel, message);
        log.debug("채팅 메시지 발행: channel={}", channel);
    }
}
