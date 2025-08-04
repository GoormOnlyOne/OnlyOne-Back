package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserScheduleRepository extends JpaRepository<UserSchedule,Long> {
    Optional<UserSchedule> findByUserAndSchedule(User user, Schedule schedule);
    int countBySchedule(Schedule schedule);
    List<UserSchedule> findUserSchedulesBySchedule(Schedule schedule);

    @Query("SELECT us.user FROM UserSchedule us WHERE us.schedule = :schedule")
    List<User> findUsersBySchedule(@Param("schedule") Schedule schedule);

    @Query("SELECT us.user FROM UserSchedule us WHERE us.schedule = :schedule AND us.scheduleRole = :role")
    Optional<User> findLeaderByScheduleAndScheduleRole(@Param("schedule") Schedule schedule, @Param("scheduleRole") ScheduleRole role);
}
