package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * IN_PROGRESS 상태로 장시간 방치된 정산 건을 FAILED로 복구하는 스케줄러.
 * Kafka 소비 실패, 어플리케이션 재시작 등으로 IN_PROGRESS에 머물 수 있는 상황을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRecoveryScheduler {

    private static final int STUCK_THRESHOLD_MINUTES = 5;

    private final SettlementRepository settlementRepository;

    @Scheduled(fixedDelay = 300_000) // 5분마다
    @Transactional
    public void recoverStuckSettlements() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        int recovered = settlementRepository.revertStuckToFailed(threshold);
        if (recovered > 0) {
            log.warn("Stuck settlement 복구: {}건을 FAILED로 전환 (threshold={})", recovered, threshold);
        }
    }
}
