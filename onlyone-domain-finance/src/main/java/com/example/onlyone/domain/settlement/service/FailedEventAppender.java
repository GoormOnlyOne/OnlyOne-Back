package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.dto.event.OutboxEvent;
import com.example.onlyone.domain.settlement.dto.event.UserSettlementStatusEvent;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FailedEventAppender {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final UserSettlementRepository userSettlementRepository;

    // Fix 5: 상태 업데이트를 이 REQUIRES_NEW 트랜잭션으로 흡수하여 롤백 방지
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendFailedUserSettlementEvent(Long settlementId,
                                                Long userSettlementId,
                                                Long participantId,
                                                Long memberWalletId,
                                                Long leaderId,
                                                Long leaderWalletId,
                                                Long amount) {
        try {
            // Fix 5: 상태 업데이트를 별도 REQUIRES_NEW tx에서 수행하여 원본 tx 롤백에 영향받지 않음
            userSettlementRepository.updateStatusIfRequested(userSettlementId, SettlementStatus.FAILED);

            // 1. DTO로 변환
            UserSettlementStatusEvent eventDto = new UserSettlementStatusEvent(
                    UserSettlementStatusEvent.ResultType.FAILED,
                    "stl:%d:usr:%d:v1".formatted(settlementId, participantId),
                    Instant.now(),
                    settlementId,
                    userSettlementId,
                    participantId,
                    memberWalletId,
                    leaderId,
                    leaderWalletId,
                    amount
            );

            // 2. JSON 직렬화
            String json = objectMapper.writeValueAsString(eventDto);

            // 3. OutboxEvent 저장
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("UserSettlement")
                    .aggregateId(userSettlementId)
                    .eventType("ParticipantSettlementResult")
                    .keyString(String.valueOf(memberWalletId)) // partition key
                    .payload(json)
                    .status(OutboxStatus.NEW)
                    .createdAt(LocalDateTime.now())
                    .build();

            outboxRepository.save(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to append FAILED event to Outbox", e);
        }
    }
}
