package com.example.onlyone.domain.settlement.event;

import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "outbox_event", indexes = {
            @Index(name = "idx_outbox_new", columnList = "status,id")
    })
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateType;  // "UserSettlement"
    private Long aggregateId; // userSettlementId
    private String eventType; // "ParticipantSettlementResult"
    private String keyString; // partition key (e.g., memberWalletId)

    @Lob
    private String payload; // JSON

    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    private int retryCount;

    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
