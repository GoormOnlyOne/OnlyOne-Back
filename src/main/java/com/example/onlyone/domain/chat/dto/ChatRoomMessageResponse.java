package com.example.onlyone.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "채팅방 정보 + 메시지 목록 응답 DTO")
public class ChatRoomMessageResponse {

    @Schema(description = "채팅방 ID")
    private Long chatRoomId;

    @Schema(description = "채팅방 이름")
    private String chatRoomName;

    @Schema(description = "메시지 목록")
    private List<ChatMessageResponse> messages;
}

