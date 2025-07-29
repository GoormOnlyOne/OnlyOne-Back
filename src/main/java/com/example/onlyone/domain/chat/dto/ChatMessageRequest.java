package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

// ‘클라이언트 → 서버’ STOMP Publish 요청 페이로드
@Setter
@Getter
public class ChatMessageRequest {

    @JsonProperty("user_id")
    private String userId;

    /**
     * text 가 null 이면 이미지 메시지,
     * text 가 not null 이면 텍스트 메시지
     */
    @JsonProperty("text")
    private String text;

    // Jackson 바인딩을 위해 기본 생성자 필수
    public ChatMessageRequest() {}

    public ChatMessageRequest(String userId, String text) {
        this.userId = userId;
        this.text   = text;
    }

}
