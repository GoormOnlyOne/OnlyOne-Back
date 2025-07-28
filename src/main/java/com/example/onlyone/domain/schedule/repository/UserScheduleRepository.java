package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.schedule.entity.UserSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserScheduleRepository extends JpaRepository<UserSchedule,Long> {
}
