package com.example.onlyone.domain.settlement.repository;

// TODO: 순환 의존성 방지 - Schedule 엔티티 대신 scheduleId 사용
// import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
    List<Settlement> findAllByTotalStatus(TotalStatus totalStatus);

    // 정산은 scheduleId로 조회 (Schedule 엔티티 불필요)
    Optional<Settlement> findByScheduleId(Long scheduleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE settlement
           SET total_status = 'IN_PROGRESS'
         WHERE settlement_id = :id
           AND total_status in ('HOLDING', 'FAILED')
    """, nativeQuery = true)
    int markProcessing(@Param("id") Long settlementId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE settlement SET total_status = 'COMPLETED', completed_time = :time WHERE settlement_id = :id AND total_status = 'IN_PROGRESS'", nativeQuery = true)
    int markCompleted(@Param("id") Long id, @Param("time") LocalDateTime time);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE settlement SET total_status = 'FAILED' WHERE settlement_id = :id AND total_status = 'IN_PROGRESS'", nativeQuery = true)
    int revertToFailed(@Param("id") Long settlementId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Settlement s where s.scheduleId = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
