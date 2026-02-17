package com.example.onlyone.common.event;

/**
 * 일정 참여 취소 이벤트
 * - Chat 도메인: UserChatRoom 삭제
 * - Settlement 도메인: UserSettlement 삭제
 */
public record ScheduleLeftEvent(Long scheduleId, Long clubId, Long userId) {
}
