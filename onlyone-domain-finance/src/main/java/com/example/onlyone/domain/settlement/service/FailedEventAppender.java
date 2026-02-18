package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.dto.event.FailedSettlementContext;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendFailedUserSettlementEvent(FailedSettlementContext ctx) {
        try {
            userSettlementRepository.updateStatusIfRequested(ctx.userSettlementId(), SettlementStatus.FAILED);

            UserSettlementStatusEvent eventDto = new UserSettlementStatusEvent(
                    UserSettlementStatusEvent.ResultType.FAILED,
                    "stl:%d:usr:%d:v1".formatted(ctx.settlementId(), ctx.participantId()),
                    Instant.now(),
                    ctx.settlementId(),
                    ctx.userSettlementId(),
                    ctx.participantId(),
                    ctx.memberWalletId(),
                    ctx.leaderId(),
                    ctx.leaderWalletId(),
                    ctx.amount()
            );

            String json = objectMapper.writeValueAsString(eventDto);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("UserSettlement")
                    .aggregateId(ctx.userSettlementId())
                    .eventType("ParticipantSettlementResult")
                    .keyString(String.valueOf(ctx.memberWalletId()))
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
