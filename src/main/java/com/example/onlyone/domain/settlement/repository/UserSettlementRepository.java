package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserSettlementRepository extends JpaRepository<UserSettlement,Long> {
    Optional<UserSettlement> findByUserAndSettlement(User user, Settlement settlement);

    long countBySettlement(Settlement settlement);

    long countBySettlementAndSettlementStatus(Settlement settlement, SettlementStatus settlementStatus);

    @Query("""
    select new com.example.onlyone.domain.settlement.dto.response.UserSettlementDto(
      u.userId, u.nickname, u.profileImage, us.settlementStatus
    )
    from UserSettlement us
    join us.user u
    where us.settlement = :settlement
    """)
    Page<UserSettlementDto> findAllDtoBySettlement(Settlement settlement, Pageable pageable);

    @Query("""
    SELECT us FROM UserSettlement us
    JOIN us.settlement s
    WHERE us.user = :user AND s.schedule = :schedule
    """)
    Optional<UserSettlement> findByUserAndSchedule(User user, Schedule schedule);
}
