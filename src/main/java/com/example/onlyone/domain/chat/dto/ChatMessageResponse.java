package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.Message;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

// ‘서버 → 클라이언트’ STOMP Subscribe 응답 페이로드
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {

    @JsonProperty("message_id")
    private Long message_id;

    @JsonProperty("chat_id")
    private Long chat_id;

    @JsonProperty("user_id")
    private Long user_id;

    private String text;

    @JsonProperty("sendAt")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime sentAt;

    private boolean deleted;

    public static ChatMessageResponse from(Message message) {
        return ChatMessageResponse.builder()
                .message_id(message.getMessageId())
                .chat_id(message.getChatRoom().getChatRoomId())
                .message_id(message.getUser().getUserId())
                .text(message.getText())
                .sentAt(message.getSentAt())
                .deleted(message.isDeleted())
                .build();
    }
}