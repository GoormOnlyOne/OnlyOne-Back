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

    public void publish(Long roomId, String message) {
        if (roomId == null || message == null || message.isBlank()) {
            log.debug("ChatPublisher 무시: roomId={}, message blank={}", roomId, message == null || message.isBlank());
            return;
        }
        String channel = "chat.room." + roomId;
        redisTemplate.convertAndSend(channel, message);
        log.debug("채팅 메시지 발행: channel={}", channel);
    }
}
