package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "채팅방 메시지 목록 응답")
public record ChatRoomMessageResponse(
    @Schema(description = "채팅방 ID") Long chatRoomId,
    @Schema(description = "채팅방 이름") String chatRoomName,
    @Schema(description = "메시지 목록(오름차순: 오래된 → 최신)") List<ChatMessageResponse> messages,
    @Schema(description = "다음 페이지가 더 있는지 여부") Boolean hasMore,
    @Schema(description = "다음 페이지 조회용 커서(메시지 ID)") Long nextCursorId,
    @Schema(description = "다음 페이지 조회용 커서(메시지 시간)")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime nextCursorAt
) {
}
