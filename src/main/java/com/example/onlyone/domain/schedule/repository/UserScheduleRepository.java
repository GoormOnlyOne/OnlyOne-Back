package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserScheduleRepository extends JpaRepository<UserSchedule,Long> {
    Optional<UserSchedule> findByUserAndSchedule(User user, Schedule schedule);
}
