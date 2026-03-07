package com.example.onlyone.common.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일정 완료 이벤트
 * - Settlement 도메인에서 구독하여 정산 생성
 */
public record ScheduleCompletedEvent(
        Long scheduleId,
        Long clubId,
        Long leaderUserId,
        List<Long> participantUserIds,  // 참여자 userId 목록
        Long totalCost,
        LocalDateTime completedAt
) {
}
