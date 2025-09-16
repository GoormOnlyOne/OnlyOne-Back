package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "채팅방 메시지 목록 응답")
public class ChatRoomMessageResponse {

    @Schema(description = "채팅방 ID")
    private Long chatRoomId;

    @Schema(description = "채팅방 이름")
    private String chatRoomName;

    @Schema(description = "메시지 목록(오름차순: 오래된 → 최신)")
    private List<ChatMessageResponse> messages;

    // ▼ 커서 기반 페이지네이션 메타데이터
    @Schema(description = "다음 페이지가 더 있는지 여부")
    private Boolean hasMore;

    @Schema(description = "다음 페이지 조회용 커서(메시지 ID)")
    private Long nextCursorId;

    @Schema(description = "다음 페이지 조회용 커서(메시지 시간)")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextCursorAt;
}

