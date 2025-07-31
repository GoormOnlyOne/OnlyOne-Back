package com.example.onlyone.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // Global
    INVALID_INPUT_VALUE(400, "GLOBAL_400_1", "입력값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED(405, "GLOBAL_400_2", "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(500, "GLOBAL_500_1", "서버 내부 오류가 발생했습니다."),
    BAD_REQUEST(400, "GLOBAL_400_3", "필수 파라미터가 누락되었습니다."),

    // User
    USER_NOT_FOUND(404, "USER_404_1", "유저를 찾을 수 없습니다."),

    // Interest
    INVALID_CATEGORY(400, "INTEREST_400_1", "유효하지 않은 카데고리입니다."),
    INTEREST_NOT_FOUND(404, "INTEREST_404_1", "관심사를 찾을 수 없습니다."),

    // Club
    INVALID_ROLE(400, "CLUB_400_1", "유효하지 않은 모임 역할입니다."),
    CLUB_NOT_FOUND(404, "CLUB_404_1", "모임이 존재하지 않습니다."),
    USER_CLUB_NOT_FOUND(400,"CLUB_404_2", "유저 모임을 찾을 수 없습니다."),
    ALREADY_JOINED_CLUB(400,"CLUB_409_1","이미 참여하고 있는 모임입니다."),
    CLUB_NOT_LEAVE(400,"CLUB_409_2","참여하지 않은 모임은 나갈 수 없습니다."),
    CLUB_LEADER_NOT_LEAVE(400, "CLUB_409_3", "모임장은 모임을 나갈 수 없습니다."),
    CLUB_NOT_ENTER(400, "CLUB_409_4", "정원이 초과하여 모임에 가입할 수 없습니다."),

    // Notification
    NOTIFICATION_TYPE_NOT_FOUND(404, "NOTIFY_404_1", "알림 타입을 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(404, "NOTIFY_404_2", "알림이 존재하지 않습니다."),
    SSE_CONNECTION_FAILED(503, "NOTIFY_503_1", "SSE 연결에 실패했습니다."),

    // Schedule
    SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_1", "정기 모임을 찾을 수 없습니다."),
    USER_SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_2", "정기 모임 참여자를 찾을 수 없습니다."),
    ALREADY_JOINED_SCHEDULE(409, "SCHEDULE_409_1", "이미 참여하고 있는 스케줄입니다."),
    LEADER_CANNOT_LEAVE_SCHEDULE(409, "SCHEDULE_409_2", "리더는 스케줄 참여를 취소할 수 없습니다."),
    MEMBER_CANNOT_MODIFY_SCHEDULE(403, "SCHEDULE_409_3", "리더만 스케줄을 수정할 수 있습니다"),

    // Chat
    CHAT_ROOM_NOT_FOUND(404, "CHAT_404_1", "채팅방을 찾을 수 없습니다."),
    USER_CHAT_ROOM_NOT_FOUND(404, "CHAT_404_2", "채팅방 참여자를 찾을 수 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
