package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.wallet.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer,Long> {
}
