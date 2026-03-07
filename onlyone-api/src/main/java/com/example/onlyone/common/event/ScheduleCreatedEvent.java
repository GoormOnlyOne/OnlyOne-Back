package com.example.onlyone.common.event;

import java.time.LocalDateTime;

/**
 * 일정 생성 이벤트
 * - Chat 도메인에서 구독하여 일정 전용 채팅방 생성
 */
public record ScheduleCreatedEvent(
        Long scheduleId,
        Long clubId,
        Long leaderUserId,
        String scheduleName,
        LocalDateTime scheduleTime
) {
}
