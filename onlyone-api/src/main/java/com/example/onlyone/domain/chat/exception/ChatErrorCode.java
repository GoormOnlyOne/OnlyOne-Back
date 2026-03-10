package com.example.onlyone.domain.chat.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChatErrorCode implements ErrorCode {

    CHAT_ROOM_NOT_FOUND(404, "CHAT_404_1", "채팅방을 찾을 수 없습니다."),
    USER_CHAT_ROOM_NOT_FOUND(404, "CHAT_404_2", "채팅방 참여자를 찾을 수 없습니다."),
    CHAT_ROOM_DELETE_FAILED(409, "CHAT_409_2", "채팅방 삭제에 실패했습니다."),
    UNAUTHORIZED_CHAT_ACCESS(401, "CHAT_401_1", "채팅방 접근 권한이 없습니다."),
    INTERNAL_CHAT_SERVER_ERROR(500, "CHAT_500_1", "채팅 서버 오류가 발생했습니다."),
    INVALID_CHAT_REQUEST(400, "CHAT_400_1", "유효하지 않은 채팅 요청입니다."),
    DUPLICATE_CHAT_ROOM(409, "CHAT_409_1", "이미 존재하는 채팅방입니다."),
    FORBIDDEN_CHAT_ROOM(403, "CHAT_403_1", "해당 채팅방 접근이 거부되었습니다."),
    MESSAGE_BAD_REQUEST(400, "CHAT_400_2", "채팅 메시지 요청이 유효하지 않습니다."),
    MESSAGE_SERVER_ERROR(500, "CHAT_500_2", "메시지 조회 중 오류가 발생했습니다."),
    MESSAGE_NOT_FOUND(404, "CHAT_404_3", "메시지를 찾을 수 없습니다."),
    MESSAGE_FORBIDDEN(403, "CHAT_403_2", "해당 메시지 삭제 권한이 없습니다."),
    MESSAGE_CONFLICT(409, "CHAT_409_3", "메시지 삭제 중 충돌이 발생했습니다."),
    MESSAGE_DELETE_ERROR(500, "CHAT_500_3", "메시지 삭제 중 서버 오류가 발생했습니다."),
    INVALID_IMAGE_CONTENT_TYPE(400, "IMAGE_400_2", "유효하지 않은 이미지 컨텐츠 타입입니다.");

    private final int status;
    private final String code;
    private final String message;
}
