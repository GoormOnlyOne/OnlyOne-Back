package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
}
