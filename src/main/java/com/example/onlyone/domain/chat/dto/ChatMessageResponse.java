package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.Message;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDateTime;

// ‘서버 → 클라이언트’ STOMP Subscribe 응답 페이로드

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "채팅 메시지 응답 DTO")
public class ChatMessageResponse {

    @Schema(description = "메시지 ID", example = "1")
    private Long messageId;

    @Schema(description = "채팅방 ID", example = "1")
    private Long chatRoomId;

    @Schema(description = "보낸 사용자 ID", example = "1")
    private Long senderId;

    @Schema(description = "보낸 사용자 닉네임", example = "닉네임")
    private String senderNickname;

    @Schema(description = "보낸 사용자 프로필 이미지 URL", example = "https://example.com/image.jpg")
    private String profileImage;

    @Schema(description = "메시지 내용", example = "안녕하세요!")
    private String text;

    @Schema(description = "메시지 첨부 이미지")
    private String imageUrl;

    @Schema(description = "전송 시각", example = "2025-07-29T11:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime sentAt;

    @Schema(description = "삭제 여부", example = "false")
    private boolean deleted;

    public static ChatMessageResponse from(Message message) {
        String text = message.getText();
        String imageUrl = null;

        // 메시지가 "[IMAGE]..." 형태라면 이미지 URL 추출
        if (text != null && text.startsWith("[IMAGE]")) {
            imageUrl = text.substring(7); // "[IMAGE]" 이후가 실제 URL
            text = null; // 텍스트는 없음
        }

        return ChatMessageResponse.builder()
                .messageId(message.getMessageId())
                .chatRoomId(message.getChatRoom().getChatRoomId())
                .senderId(message.getUser().getUserId())
                .senderNickname(message.getUser().getNickname())
                .profileImage(message.getUser().getProfileImage())
                .text(text)
                .imageUrl(imageUrl)
                .sentAt(message.getSentAt())
                .deleted(message.isDeleted())
                .build();
    }

}
