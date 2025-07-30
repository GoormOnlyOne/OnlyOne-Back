package com.example.onlyone.domain.chat.dto;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "채팅방 단건 상세 응답 DTO")
public class ChatRoomDetailResponse {

    @Schema(description = "채팅방 ID", example = "6")
    private Long chatRoomId;

    @Schema(description = "클럽 ID", example = "3")
    private Long clubId;

    @Schema(description = "스케줄 ID (없을 수도 있음)", example = "10")
    private Long scheduleId;

    @Schema(description = "채팅방 타입", example = "CLUB")
    private Type type;

    public static ChatRoomDetailResponse from(ChatRoom chatRoom) {
        return ChatRoomDetailResponse.builder()
                .chatRoomId(chatRoom.getChatRoomId())
                .clubId(chatRoom.getClub().getClubId())
                .scheduleId(chatRoom.getScheduleId())
                .type(chatRoom.getType())
                .build();
    }
}
