package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산(Settlement) 조회 서비스 — 정산 목록 조회
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SettlementQueryService {

    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;

    /** 스케줄 참여자 정산 목록 조회 */
    public SettlementResponseDto getSettlementList(Long scheduleId, Pageable pageable) {
        Settlement settlement = settlementRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        Page<UserSettlementDto> userSettlementList = userSettlementRepository
                .findAllDtoBySettlement(settlement, pageable);
        return SettlementResponseDto.from(userSettlementList);
    }
}
