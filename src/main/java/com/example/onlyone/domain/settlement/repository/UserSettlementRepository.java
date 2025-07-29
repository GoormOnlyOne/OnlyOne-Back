package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.entity.UserSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettlementRepository extends JpaRepository<UserSettlement,Long> {
}
