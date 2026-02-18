package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import com.example.onlyone.global.common.util.MessageUtils;

@Schema(description = "채팅방 응답 DTO")
public record ChatRoomResponse(
    @Schema(description = "채팅방 ID", example = "1") Long chatRoomId,
    @Schema(description = "채팅방 이름") String chatRoomName,
    @Schema(description = "클럽 ID", example = "1") Long clubId,
    @Schema(description = "스케줄 ID (정모 채팅방일 경우)", example = "null") Long scheduleId,
    @Schema(description = "채팅방 타입 (CLUB, SCHEDULE)", example = "CLUB") ChatRoomType type,
    @Schema(description = "최근 메시지 내용", example = "안녕하세요!") String lastMessageText,
    @Schema(description = "최근 메시지 시간", example = "2025-08-03T20:10:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastMessageTime
) {
    public static ChatRoomResponse from(ChatRoom chatRoom, Message lastMessage) {
        String messageText = null;
        if (lastMessage != null && !lastMessage.isDeleted()) {
            messageText = MessageUtils.getDisplayText(lastMessage.getText());
        }

        Long scheduleId = (chatRoom.getType() == ChatRoomType.SCHEDULE && chatRoom.getSchedule() != null)
                ? chatRoom.getSchedule().getScheduleId() : null;

        return new ChatRoomResponse(
                chatRoom.getChatRoomId(),
                chatRoom.resolveName(),
                chatRoom.getClub().getClubId(),
                scheduleId,
                chatRoom.getType(),
                messageText,
                lastMessage != null ? lastMessage.getSentAt() : null
        );
    }
}
