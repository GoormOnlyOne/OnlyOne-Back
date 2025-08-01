package com.example.onlyone.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // Global
    INVALID_INPUT_VALUE(400, "GLOBAL_400_1", "입력값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED(405, "GLOBAL_400_2", "지원하지 않는 HTTP 메서드입니다."),
    BAD_REQUEST(400, "GLOBAL_400_3", "필수 파라미터가 누락되었습니다."),
    INTERNAL_SERVER_ERROR(500, "GLOBAL_500_1", "서버 내부 오류가 발생했습니다."),

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

    // Schedule
    SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_1", "정기 모임을 찾을 수 없습니다."),
    USER_SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_2", "정기 모임 참여자를 찾을 수 없습니다."),
    LEADER_NOT_FOUND(404, "SCHEDULE_404_3", "정기 모임 리더를 찾을 수 없습니다."),
    ALREADY_JOINED_SCHEDULE(409, "SCHEDULE_409_1", "이미 참여하고 있는 정기 모임입니다."),
    LEADER_CANNOT_LEAVE_SCHEDULE(409, "SCHEDULE_409_2", "리더는 정기 모임 참여를 취소할 수 없습니다."),
    MEMBER_CANNOT_MODIFY_SCHEDULE(403, "SCHEDULE_403_1", "리더만 정기 모임을 수정할 수 있습니다,"),
    ALREADY_ENDED_SCHEDULE(409, "SCHEDULE_409_4", "이미 종료된 정기 모임입니다."),
    BEFORE_SCHEDULE_START(409, "SCHEDULE_409_5", "아직 진행되지 않은 정기 모임입니다."),
    ALREADY_EXCEEDED_SCHEDULE(409, "SCHEDULE_409_6", "이미 정원이 마감된 정기 모임입니다."),

    // Settlement
    MEMBER_CANNOT_CREATE_SETTLEMENT(403, "SETTLEMENT_403_1", "리더만 정산 요청을 할 수 있습니다."),
    SETTLEMENT_NOT_FOUND(404, "SETTLEMENT_404_1", "정산을 찾을 수 없습니다."),
    USER_SETTLEMENT_NOT_FOUND(404, "SETTLEMENT_404_2", "정산 참여자를 찾을 수 없습니다."),
    ALREADY_SETTLED_USER(409, "SETTLEMENT_409_1", "이미 해당 정기 모임에 대해 정산한 유저입니다."),

    // Chat
    CHAT_ROOM_NOT_FOUND(404, "CHAT_404_1", "채팅방을 찾을 수 없습니다."),
    USER_CHAT_ROOM_NOT_FOUND(404, "CHAT_404_2", "채팅방 참여자를 찾을 수 없습니다."),

    // Wallet
    INVALID_FILTER(400, "WALLET_400_1", "유효하지 않은 필터입니다."),
    WALLET_NOT_FOUND(404, "WALLET_404_1", "사용자의 지갑을 찾을 수 없습니다."),
    WALLET_BALANCE_NOT_ENOUGH(409, "WALLET_409_1", "사용자의 잔액이 부족합니다."),

    // Payment
    INVALID_PAYMENT_INFO(400, "PAYMENT_400_1", "결제 금액 정보가 유효하지 않습니다."),
  
    // Chat - 채팅방 목록 조회
    UNAUTHORIZED_CHAT_ACCESS(401, "CHAT_401_1", "채팅방 접근 권한이 없습니다."),
    INTERNAL_CHAT_SERVER_ERROR(500, "CHAT_500_1", "채팅 서버 오류가 발생했습니다."),

    // Chat - 채팅방 생성
    INVALID_CHAT_REQUEST(400, "CHAT_400_1", "유효하지 않은 채팅 요청입니다."),
    DUPLICATE_CHAT_ROOM(409, "CHAT_409_1", "이미 존재하는 채팅방입니다."),

    // Chat - 채팅 메시지 목록 조회
    FORBIDDEN_CHAT_ROOM(403, "CHAT_403_1", "해당 채팅방 접근이 거부되었습니다."),
    MESSAGE_BAD_REQUEST(400, "CHAT_400_2", "채팅 메시지 요청이 유효하지 않습니다."),
    MESSAGE_SERVER_ERROR(500, "CHAT_500_2", "메시지 조회 중 오류가 발생했습니다."),

    // Chat - 메시지 삭제
    MESSAGE_FORBIDDEN(403, "CHAT_403_2", "해당 메시지 삭제 권한이 없습니다."),
    MESSAGE_CONFLICT(409, "CHAT_409_1", "메시지 삭제 중 충돌이 발생했습니다."),
    MESSAGE_DELETE_ERROR(500, "CHAT_500_3", "메시지 삭제 중 서버 오류가 발생했습니다."),

    // Feed
    FEED_NOT_FOUND(404, "FEED_404_1","피드를 찾을 수 없습니다."),
    UNAUTHORIZED_FEED_ACCESS(403, "FEED_403_1", "해당 피드에 대한 권한이 없습니다."),
    COMMENT_NOT_FOUND(404, "FEED_404_2", "댓글을 찾을 수 없습니다."),
    UNAUTHORIZED_COMMENT_ACCESS(403, "FEED_403_2", "해당 댓글에 대한 권한이 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
