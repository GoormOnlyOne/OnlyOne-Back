package com.example.onlyone.common.event;

/**
 * 일정 삭제 이벤트
 * - Chat 도메인: 일정 채팅방 및 UserChatRoom 모두 삭제
 * - Settlement 도메인: Settlement 및 UserSettlement 모두 삭제
 */
public record ScheduleDeletedEvent(Long scheduleId, Long clubId) {
}
