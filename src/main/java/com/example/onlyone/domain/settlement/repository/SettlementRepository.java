package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
    List<Settlement> findAllByTotalStatus(TotalStatus totalStatus);
    Optional<Settlement> findBySchedule(Schedule schedule);
}
