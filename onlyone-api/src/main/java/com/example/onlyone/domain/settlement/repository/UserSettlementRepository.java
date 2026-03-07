package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserSettlementRepository extends JpaRepository<UserSettlement, Long> {

    Optional<UserSettlement> findByUserAndSettlement(User user, Settlement settlement);

    long countBySettlement(Settlement settlement);

    long countBySettlementAndSettlementStatus(Settlement settlement, SettlementStatus settlementStatus);

    @Query(
            value = """
        select new com.example.onlyone.domain.settlement.dto.response.UserSettlementDto(
          u.userId, u.nickname, u.profileImage, us.settlementStatus
        )
        from UserSettlement us
        join us.user u
        where us.settlement = :settlement
        order by us.createdAt desc
        """,
            countQuery = """
        select count(us)
        from UserSettlement us
        where us.settlement = :settlement
        """
    )
    Page<UserSettlementDto> findAllDtoBySettlement(
            @Param("settlement") Settlement settlement,
            Pageable pageable
    );

    boolean existsByUserAndSettlementStatusNot(User user, SettlementStatus settlementStatus);

    @Modifying
    @Query("update UserSettlement us set us.settlementStatus = :status " +
            "where us.userSettlementId = :id")
    void updateStatusIfRequested(@Param("id") Long userSettlementId, @Param("status") SettlementStatus settlementStatus);

    List<UserSettlement> findAllBySettlement_SettlementIdAndSettlementStatus(
            Long settlementId, SettlementStatus settlementStatus);

    @Query("""
    SELECT us.user.userId
    FROM UserSettlement us
    WHERE us.settlement.settlementId = :settlementId
      AND us.settlementStatus = :settlementStatus
""")
    List<Long> findUserIdsBySettlementIdAndStatus(
            @Param("settlementId") Long settlementId,
            @Param("settlementStatus") SettlementStatus settlementStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserSettlement us where us.settlement.settlementId = :settlementId")
    void deleteAllBySettlementId(@Param("settlementId") Long settlementId);

    /**
     * 배치 완료 처리 — 한 번의 UPDATE로 여러 참가자의 정산 상태를 COMPLETED로 전이
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE user_settlement
           SET status = 'COMPLETED', completed_time = :now
         WHERE settlement_id = :settlementId
           AND user_id IN (:userIds)
           AND status = 'HOLD_ACTIVE'
    """, nativeQuery = true)
    int batchMarkCompleted(@Param("settlementId") Long settlementId,
                           @Param("userIds") List<Long> userIds,
                           @Param("now") java.time.LocalDateTime now);

    Optional<UserSettlement> findBySettlement_SettlementIdAndUser_UserId(Long settlementId, Long participantId);

    @Query(value = "SELECT user_settlement_id FROM user_settlement WHERE settlement_id = :settlementId AND user_id = :userId", nativeQuery = true)
    Long findUserSettlementId(@Param("settlementId") Long settlementId, @Param("userId") Long userId);

    @Query(value = "SELECT user_id, user_settlement_id FROM user_settlement WHERE settlement_id = :settlementId AND user_id IN (:userIds)", nativeQuery = true)
    List<Object[]> findUserSettlementIdsBySettlementIdAndUserIds(@Param("settlementId") Long settlementId, @Param("userIds") List<Long> userIds);
}
