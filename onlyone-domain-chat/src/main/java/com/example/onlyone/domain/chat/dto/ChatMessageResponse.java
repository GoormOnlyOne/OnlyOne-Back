package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.global.common.util.MessageUtils;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

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
    /**
     * WebSocket 실시간 전송용 (DB 저장 전, IMAGE:: 프리픽스 파싱)
     */
    public static ChatMessageResponse forWebSocket(Long chatRoomId, Long senderId,
                                                    String senderNickname, String profileImage,
                                                    String rawText) {
        String imageUrl = MessageUtils.extractImageUrl(rawText);
        String text = (imageUrl != null) ? null : rawText;

        return new ChatMessageResponse(null, chatRoomId, senderId, senderNickname, profileImage,
                text, imageUrl, LocalDateTime.now(), false);
    }

    /**
     * DB 엔티티 → 응답 DTO (IMAGE:: 프리픽스로 이미지 판별)
     */
    public static ChatMessageResponse from(Message message) {
        String rawText = message.getText();
        String imageUrl = MessageUtils.extractImageUrl(rawText);
        String text = (imageUrl != null) ? null : rawText;

        return new ChatMessageResponse(
                message.getMessageId(),
                message.getChatRoom().getChatRoomId(),
                message.getUser().getUserId(),
                message.getUser().getNickname(),
                message.getUser().getProfileImage(),
                text,
                imageUrl,
                message.getSentAt(),
                message.isDeleted()
        );
    }
}
