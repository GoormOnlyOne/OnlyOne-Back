package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// ‘서버 → 클라이언트’ STOMP Subscribe 응답 페이로드
@Setter
@Getter
public class ChatMessageResponse {

    @JsonProperty("message_id")
    private Long messageId;

    @JsonProperty("chat_id")
    private Long chatId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("text")
    private String text;

    /** ISO-8601 UTC 타임스탬프 예: "2025-07-25T10:10:00Z" */
    @JsonProperty("sendAt")
    private Instant sendAt;

    @JsonProperty("deleted")
    private Boolean deleted;

    public ChatMessageResponse() {}

    public ChatMessageResponse(
            Long messageId,
            Long chatId,
            String userId,
            String text,
            Instant sendAt,
            Boolean deleted
    ) {
        this.messageId = messageId;
        this.chatId    = chatId;
        this.userId    = userId;
        this.text      = text;
        this.sendAt    = sendAt;
        this.deleted   = deleted;
    }

    // == getters / setters ==

}
