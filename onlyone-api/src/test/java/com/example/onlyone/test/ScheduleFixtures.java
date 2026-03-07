package com.example.onlyone.test;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;

import java.time.LocalDateTime;

public final class ScheduleFixtures {

    private ScheduleFixtures() {
    }

    public static Schedule.ScheduleBuilder aSchedule(Club club) {
        return Schedule.builder()
                .scheduleId(1L)
                .name("테스트 일정")
                .location("서울 강남역")
                .cost(10000L)
                .userLimit(5)
                .scheduleStatus(ScheduleStatus.READY)
                .scheduleTime(LocalDateTime.now().plusDays(7))
                .club(club);
    }

    public static Schedule.ScheduleBuilder aSchedule(Long id, Club club) {
        return aSchedule(club)
                .scheduleId(id)
                .name("테스트 일정" + id);
    }

    public static UserSchedule.UserScheduleBuilder aUserSchedule(User user, Schedule schedule, ScheduleRole role) {
        return UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(role);
    }
}
