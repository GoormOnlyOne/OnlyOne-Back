package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "채팅 메시지 전송 요청 DTO")
public record ChatMessageRequest(
        @Schema(description = "보내는 사용자 ID(=카카오 ID)", example = "1001")
        Long userId,

        @Schema(description = "메시지 내용", example = "안녕하세요!")
        String text,

        @Schema(description = "메시지 이미지 URL", example = "https://cdn.example.com/chat/abc.jpg")
        String imageUrl
) {
    /**
     * 공통 팩토리: 공백을 null로 정규화하고 텍스트/이미지 동시 입력을 막는다.
     */
    public static ChatMessageRequest from(Long userId, String text, String imageUrl) {
        String normText = normalize(text);
        String normImage = normalize(imageUrl);

        if (normText != null && normImage != null) {
            throw new IllegalArgumentException("텍스트와 이미지 중 하나만 전송할 수 있습니다.");
        }

        return new ChatMessageRequest(userId, normText, normImage);
    }

    /** 텍스트 전용 팩토리 */
    public static ChatMessageRequest fromText(Long userId, String text) {
        return from(userId, text, null);
    }

    /** 이미지 전용 팩토리 */
    public static ChatMessageRequest fromImage(Long userId, String imageUrl) {
        return from(userId, null, imageUrl);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
