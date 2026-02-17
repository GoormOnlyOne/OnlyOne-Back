package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.Message;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import com.example.onlyone.global.common.util.MessageUtils;

@Schema(description = "채팅 메시지 응답 DTO")
public record ChatMessageResponse(
    @Schema(description = "메시지 ID", example = "1") Long messageId,
    @Schema(description = "채팅방 ID", example = "1") Long chatRoomId,
    @Schema(description = "보낸 사용자 ID", example = "1") Long senderId,
    @Schema(description = "보낸 사용자 닉네임", example = "닉네임") String senderNickname,
    @Schema(description = "보낸 사용자 프로필 이미지 URL", example = "https://example.com/image.jpg") String profileImage,
    @Schema(description = "메시지 내용", example = "안녕하세요!") String text,
    @Schema(description = "메시지 첨부 이미지") String imageUrl,
    @Schema(description = "전송 시각", example = "2025-07-29T11:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime sentAt,
    @Schema(description = "삭제 여부", example = "false") boolean deleted
) {
    public static ChatMessageResponse from(Message message) {
        String rawText = message.getText();
        String text = rawText;
        String imageUrl = null;

        if (rawText != null && rawText.startsWith("http")) {
            imageUrl = rawText;
            text = null;
        }

        return new ChatMessageResponse(
                message.getMessageId(),
                message.getChatRoom().getChatRoomId(),
                message.getUser().getKakaoId(),
                message.getUser().getNickname(),
                message.getUser().getProfileImage(),
                text,
                imageUrl,
                message.getSentAt(),
                message.isDeleted()
        );
    }
}
