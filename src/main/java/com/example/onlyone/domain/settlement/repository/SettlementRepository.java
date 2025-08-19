package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
    List<Settlement> findAllByTotalStatus(TotalStatus totalStatus);
    Optional<Settlement> findBySchedule(Schedule schedule);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE settlement
           SET total_status = 'IN_PROGRESS'
         WHERE settlement_id = :id
           AND total_status = 'REQUESTED'
    """, nativeQuery = true)
    int markProcessing(@Param("id") Long settlementId);
}
