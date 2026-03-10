package com.example.onlyone.common.event;

/**
 * 일정 참여 이벤트
 * - Chat 도메인: UserChatRoom 추가
 * - Settlement 도메인: UserSettlement 생성
 */
public record ScheduleJoinedEvent(
        Long scheduleId,
        Long clubId,
        Long userId,
        Long cost  // 예약금 금액
) {
}
