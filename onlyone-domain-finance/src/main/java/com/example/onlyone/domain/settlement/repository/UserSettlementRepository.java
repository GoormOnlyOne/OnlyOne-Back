package com.example.onlyone.domain.settlement.repository;

// TODO: 순환 의존성 방지 - Schedule 엔티티 참조 제거
// import com.example.onlyone.domain.schedule.entity.Schedule;
// import com.example.onlyone.domain.user.dto.response.MySettlementDto;  // cross-domain DTO는 API 모듈로 이동
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
    
    // TODO: 정산 참여자 조회는 Settlement만으로 가능 (Schedule join 불필요)
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

    // TODO: cross-domain DTO 조회는 API 모듈의 Application Service에서 처리
    // MySettlementDto는 Schedule, Club 정보를 조합하므로 API 모듈로 이동 필요
    // @Query(
    //         value = """
    // select new com.example.onlyone.domain.user.dto.response.MySettlementDto(
    //   c.clubId,
    //       sch.scheduleId,
    //   sch.cost,
    //   c.clubImage,
    //   us.settlementStatus,
    //   concat(c.name, ': ', sch.name),
    //   us.createdAt
    // )
    // from UserSettlement us
    // join us.settlement st
    // join st.schedule sch
    // join sch.club c
    // where us.user = :user
    //   and (
    //     us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.REQUESTED
    //     or
    //     us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.FAILED
    //     or (
    //       us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.COMPLETED
    //       and us.completedTime >= :cutoff
    //     )
    //   )
    // order by us.createdAt desc
    // """,
    //         countQuery = """
    // select count(us)
    // from UserSettlement us
    // where us.user = :user
    //   and (
    //     us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.REQUESTED
    //     or
    //     us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.FAILED
    //     or (
    //       us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.COMPLETED
    //       and us.completedTime >= :cutoff
    //     )
    //   )
    // """
    // )
    // Page<MySettlementDto> findMyRecentOrRequested(
    //         @Param("user") User user,
    //         @Param("cutoff") java.time.LocalDateTime cutoff,
    //         Pageable pageable
    // );

    // TODO: scheduleId로 조회하도록 변경
    // @Query("""
    // select us
    // from UserSettlement us
    // join us.settlement s
    // where us.user = :user and s.schedule = :schedule
    // """)
    // Optional<UserSettlement> findByUserAndSchedule(
    //         @Param("user") User user,
    //         @Param("schedule") Schedule schedule
    // );
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
    List<Long> findAllUserSettlementIdsBySettlementIdAndStatus(
            @Param("settlementId") Long settlementId,
            @Param("settlementStatus") SettlementStatus settlementStatus);

    // 성능 개선: 참가자 수와 ID 목록을 한 번에 조회하는 메서드 (사용하지 않음 - 위의 메서드로 대체)
    @Query("""
    SELECT us.user.userId
    FROM UserSettlement us
    WHERE us.settlement.settlementId = :settlementId
      AND us.settlementStatus = :settlementStatus
""")
    List<Long> findActiveParticipantIds(@Param("settlementId") Long settlementId, 
                                       @Param("settlementStatus") SettlementStatus settlementStatus);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserSettlement us where us.settlement.settlementId = :settlementId")
    void deleteAllBySettlementId(@Param("settlementId") Long settlementId);

   Optional<UserSettlement> findBySettlement_SettlementIdAndUser_UserId(Long settlementId, Long participantId);
}
