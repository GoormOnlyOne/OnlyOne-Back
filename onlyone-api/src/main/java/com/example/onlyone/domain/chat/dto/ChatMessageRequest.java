package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "채팅 메시지 전송 요청 DTO")
public record ChatMessageRequest(
        @Schema(description = "메시지 내용", example = "안녕하세요!")
        String text,

        @Schema(description = "메시지 이미지 URL", example = "https://cdn.example.com/chat/abc.jpg")
        String imageUrl
) {
}
