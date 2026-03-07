package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;

/**
 * JPQL Object[] → 타입 안전 매핑을 위한 중간 프로젝션.
 * ScheduleRepository.findScheduleListWithUserInfo()의 결과를 담는다.
 */
public record ScheduleListRow(
        Schedule schedule,
        long userCount,
        ScheduleRole currentUserRole
) {
    public static ScheduleListRow from(Object[] row) {
        return new ScheduleListRow(
                (Schedule) row[0],
                (Long) row[1],
                (ScheduleRole) row[2]
        );
    }

    public boolean isJoined() {
        return currentUserRole != null;
    }

    public boolean isLeader() {
        return currentUserRole == ScheduleRole.LEADER;
    }
}
