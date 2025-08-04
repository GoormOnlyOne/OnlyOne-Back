package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.Type;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "채팅방 응답 DTO")
public class ChatRoomResponse {

    @Schema(description = "채팅방 ID", example = "1")
    private Long chatRoomId;

    @Schema(description = "클럽 ID", example = "1")
    private Long clubId;

    @Schema(description = "스케줄 ID (정모 채팅방일 경우)", example = "null")
    private Long scheduleId;

    @Schema(description = "채팅방 타입 (CLUB, SCHEDULE)", example = "CLUB")
    private Type type;

    @Schema(description = "최근 메시지 내용", example = "안녕하세요!")
    private String lastMessageText;

    @Schema(description = "최근 메시지 시간", example = "2025-08-03T20:10:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastMessageTime;

    public static ChatRoomResponse from(ChatRoom chatRoom, Message lastMessage) {
        String messageText = null;

        if (lastMessage != null && !lastMessage.isDeleted()) {
            String rawText = lastMessage.getText();
            if (rawText != null) {
                messageText = rawText.startsWith("[IMAGE]") ? "사진을 보냈습니다." : rawText;
            }
        }

        return ChatRoomResponse.builder()
                .chatRoomId(chatRoom.getChatRoomId())
                .clubId(chatRoom.getClub().getClubId())
                .scheduleId(chatRoom.getScheduleId())
                .type(chatRoom.getType())
                .lastMessageText(messageText)
                .lastMessageTime(lastMessage != null ? lastMessage.getSentAt() : null)
                .build();
    }
}