package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule,Long> {
    List<Schedule> findByClubAndScheduleStatusNot(Club club, ScheduleStatus scheduleStatus);
    List<Schedule> findByScheduleStatus(ScheduleStatus scheduleStatus);

    @Modifying
    @Query("UPDATE Schedule s SET s.scheduleStatus = :endedStatus WHERE s.scheduleStatus = :readyStatus AND s.scheduleTime < :now")
    int updateExpiredSchedules(@Param("endedStatus") ScheduleStatus endedStatus,
                               @Param("readyStatus") ScheduleStatus readyStatus,
                               @Param("now") LocalDateTime now);
}
