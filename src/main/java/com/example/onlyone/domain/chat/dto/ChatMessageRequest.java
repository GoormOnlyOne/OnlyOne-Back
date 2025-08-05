package com.example.onlyone.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "채팅 메시지 전송 요청 DTO")
public class ChatMessageRequest {

    @Schema(description = "보내는 사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "메시지 내용", example = "안녕하세요!")
    private String text;

    @Schema(description = "메시지 이미지")
    private String imageUrl;
}
