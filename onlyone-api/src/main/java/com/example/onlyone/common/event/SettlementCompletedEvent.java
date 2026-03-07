package com.example.onlyone.common.event;

import java.time.LocalDateTime;

/**
 * 정산 완료 이벤트
 * - Finance 도메인에서 발행, Schedule 도메인에서 구독하여 스케줄 상태를 CLOSED로 변경
 */
public record SettlementCompletedEvent(
        Long settlementId,
        Long scheduleId,
        Long clubId,
        LocalDateTime completedAt
) {
}
