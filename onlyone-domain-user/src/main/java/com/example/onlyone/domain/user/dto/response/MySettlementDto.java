package com.example.onlyone.domain.user.dto.response;

// import com.example.onlyone.domain.settlement.entity.SettlementStatus;  // 순환 의존성 방지

import java.time.LocalDateTime;

public record MySettlementDto(
    Long clubId,
    Long scheduleId,
    Long amount,
    String mainImage,
    String settlementStatus,  // TODO: 순환 의존성 방지를 위해 String으로 변경 (원래 SettlementStatus enum)
    String title,
    LocalDateTime createdAt
) {
}
