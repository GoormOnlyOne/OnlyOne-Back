package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

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

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return ChatRoomResponse.builder()
                .chatRoomId(chatRoom.getChatRoomId())
                .clubId(chatRoom.getClub().getClubId())
                .scheduleId(chatRoom.getScheduleId())
                .type(chatRoom.getType())
                .build();
    }
}
