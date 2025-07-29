package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
    List<Settlement> findAllByTotalStatus(TotalStatus totalStatus);
}
