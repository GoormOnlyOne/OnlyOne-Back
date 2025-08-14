package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.user.dto.response.MySettlementDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
        join us.settlement st
        join st.schedule sch
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

    @Query(
            value = """
    select new com.example.onlyone.domain.user.dto.response.MySettlementDto(
      c.clubId,                 
      sch.cost,                
      c.clubImage,               
      us.settlementStatus,         
      concat(c.name, ': ', sch.name),
      us.createdAt
    )
    from UserSettlement us
    join us.settlement st
    join st.schedule sch
    join sch.club c
    where us.user = :user
      and (
        us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.REQUESTED
        or (
          us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.COMPLETED
          and us.completedTime >= :cutoff
        )
      )
    order by us.createdAt desc
    """,
            countQuery = """
    select count(us)
    from UserSettlement us
    where us.user = :user
      and (
        us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.REQUESTED
        or (
          us.settlementStatus = com.example.onlyone.domain.settlement.entity.SettlementStatus.COMPLETED
          and us.completedTime >= :cutoff
        )
      )
    """
    )
    Page<MySettlementDto> findMyRecentOrRequested(
            @Param("user") User user,
            @Param("cutoff") java.time.LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
    select us
    from UserSettlement us
    join us.settlement s
    where us.user = :user and s.schedule = :schedule
    """)
    Optional<UserSettlement> findByUserAndSchedule(
            @Param("user") User user,
            @Param("schedule") Schedule schedule
    );
}
