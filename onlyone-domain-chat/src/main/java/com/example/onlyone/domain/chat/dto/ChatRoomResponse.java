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

        String chatRoomName;
        Long scheduleId = null;

        // SCHEDULE 채팅방일 경우에만 schedule 참조
        if (chatRoom.getType() == ChatRoomType.SCHEDULE && chatRoom.getSchedule() != null) {
            chatRoomName = chatRoom.getSchedule().getName();
            scheduleId = chatRoom.getSchedule().getScheduleId();
        } else {
            // CLUB 채팅방의 경우 club 이름 사용 (또는 기본값 설정)
            chatRoomName = chatRoom.getClub().getName(); // 또는 "모임 채팅방" 등
        }

        return new ChatRoomResponse(
                chatRoom.getChatRoomId(),
                chatRoomName,
                chatRoom.getClub().getClubId(),
                scheduleId,
                chatRoom.getType(),
                messageText,
                lastMessage != null ? lastMessage.getSentAt() : null
        );
    }
}
