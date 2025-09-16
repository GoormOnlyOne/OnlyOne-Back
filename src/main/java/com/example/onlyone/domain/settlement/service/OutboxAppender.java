package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.dto.event.OutboxEvent;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxAppender {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void append(String aggregateType, Long aggregateId,
                       String eventType, String keyString, Object payloadObj) {
        try {
            String json = (payloadObj instanceof String s) ? s : objectMapper.writeValueAsString(payloadObj);
            OutboxEvent e = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .keyString(keyString)
                    .payload(json)
                    .status(OutboxStatus.NEW)
                    .createdAt(LocalDateTime.now())
                    .build();
            outboxRepository.save(e);
        } catch (Exception ex) {
            throw new RuntimeException("Outbox append failed", ex);
        }
    }
}
