package com.example.onlyone.domain.club.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ClubErrorCode implements ErrorCode {

    INVALID_ROLE(400, "CLUB_400_1", "유효하지 않은 모임 역할입니다."),
    CLUB_NOT_FOUND(404, "CLUB_404_1", "모임이 존재하지 않습니다."),
    USER_CLUB_NOT_FOUND(400, "CLUB_404_2", "유저 모임을 찾을 수 없습니다."),
    ALREADY_JOINED_CLUB(400, "CLUB_409_1", "이미 참여하고 있는 모임입니다."),
    CLUB_NOT_LEAVE(400, "CLUB_409_2", "참여하지 않은 모임은 나갈 수 없습니다."),
    CLUB_LEADER_NOT_LEAVE(400, "CLUB_409_3", "모임장은 모임을 나갈 수 없습니다."),
    CLUB_NOT_ENTER(400, "CLUB_409_4", "정원이 초과하여 모임에 가입할 수 없습니다."),
    LEADER_ONLY_CLUB_MODIFY(403, "CLUB_403_1", "리더만 모임을 수정할 수 있습니다."),
    CLUB_NOT_JOIN(409, "FEED_409_3", "모임에 가입돼 있지 않습니다.");

    private final int status;
    private final String code;
    private final String message;
}
