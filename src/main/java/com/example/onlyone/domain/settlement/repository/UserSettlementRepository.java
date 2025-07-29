package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSettlementRepository extends JpaRepository<UserSettlement,Long> {
    Optional<UserSettlement> findByUserAndSettlement(User user, Settlement settlement);

    long countBySettlement(Settlement settlement);

    long countBySettlementAndSettlementStatus(Settlement settlement, SettlementStatus settlementStatus);
}
