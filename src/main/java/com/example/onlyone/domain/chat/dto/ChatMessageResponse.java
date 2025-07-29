package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.Message;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

// ‘서버 → 클라이언트’ STOMP Subscribe 응답 페이로드
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageResponse {
    @JsonProperty("message_id")
    private Long messageId;

    @JsonProperty("chat_id")
    private Long chatRoomId;

    @JsonProperty("user_id")
    private Long userId;

    private String text;

    @JsonProperty("sendAt")
    private LocalDateTime sentAt;

    private boolean deleted;

    public static ChatMessageResponse from(Message message) {
        return ChatMessageResponse.builder()
                .messageId(message.getMessageId())
                .chatRoomId(message.getChatRoom().getChatRoomId())
                .userId(message.getUser().getUserId())
                .text(message.getText())
                .sentAt(message.getSentAt())
                .deleted(message.isDeleted())
                .build();
    }
}
