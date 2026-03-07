package com.example.onlyone.domain.schedule.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ScheduleErrorCode implements ErrorCode {

    INVALID_SCHEDULE_DELETE(400, "SCHEDULE_400_1", "이미 시작한 스케줄은 삭제할 수 없습니다."),
    MEMBER_CANNOT_MODIFY_SCHEDULE(403, "SCHEDULE_403_1", "리더만 정기 모임을 수정할 수 있습니다,"),
    MEMBER_CANNOT_DELETE_SCHEDULE(403, "SCHEDULE_403_2", "리더만 정기 모임을 삭제할 수 있습니다,"),
    MEMBER_CANNOT_CREATE_SCHEDULE(403, "SCHEDULE_403_3", "리더만 정기 모임을 추가할 수 있습니다."),
    SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_1", "정기 모임을 찾을 수 없습니다."),
    USER_SCHEDULE_NOT_FOUND(404, "SCHEDULE_404_2", "정기 모임 참여자를 찾을 수 없습니다."),
    LEADER_NOT_FOUND(404, "SCHEDULE_404_3", "정기 모임 리더를 찾을 수 없습니다."),
    ALREADY_JOINED_SCHEDULE(409, "SCHEDULE_409_1", "이미 참여하고 있는 정기 모임입니다."),
    LEADER_CANNOT_LEAVE_SCHEDULE(409, "SCHEDULE_409_2", "리더는 정기 모임 참여를 취소할 수 없습니다."),
    ALREADY_ENDED_SCHEDULE(409, "SCHEDULE_409_4", "이미 종료된 정기 모임입니다."),
    BEFORE_SCHEDULE_END(409, "SCHEDULE_409_5", "아직 진행되지 않은 정기 모임입니다."),
    ALREADY_EXCEEDED_SCHEDULE(409, "SCHEDULE_409_6", "이미 정원이 마감된 정기 모임입니다."),
    ALREADY_SETTLING_SCHEDULE(409, "SCHEDULE_409_7", "이미 정산 진행 중인 정기 모임입니다."),
    SCHEDULE_NOT_JOIN(403, "SCHEDULE_403_4", "정기 모임(스케줄)에 참여하지 않은 사용자입니다.");

    private final int status;
    private final String code;
    private final String message;
}
