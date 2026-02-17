package com.example.onlyone.domain.settlement.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSettlementStatusEvent(
        ResultType type,              // "SUCCESS" | "FAILED"
        String operationId,           // "stl:4:usr:100234:v1"
        Instant occurredAt,
        long settlementId,
        long userSettlementId,
        long participantId,
        long memberWalletId,
        long leaderId,
        long leaderWalletId,
        long amount
) {
    public enum ResultType { SUCCESS, FAILED }
}
