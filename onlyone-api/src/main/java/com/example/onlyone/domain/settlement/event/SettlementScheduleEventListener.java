package com.example.onlyone.domain.settlement.event;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Schedule 이벤트 리스너 (Settlement 도메인)
 * - 일정 생성 시 Settlement 초기화
 * - 일정 참여 시 UserSettlement 생성
 * - 일정 참여 취소 시 UserSettlement 삭제
 * - 일정 삭제 시 Settlement 및 모든 UserSettlement 삭제
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduleEventListener {

    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final UserRepository userRepository;

    /**
     * 일정 생성 이벤트 처리
     * - Settlement 초기화 (리더가 receiver)
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleCreatedEvent(ScheduleCreatedEvent event) {
        log.info("[Event.Received] type=ScheduleCreatedEvent, target=Settlement, scheduleId={}", event.scheduleId());

        try {
            User leader = userRepository.findById(event.leaderUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.leaderUserId()));

            // Settlement 초기화 (정산 시작 시 참여자 수 * cost)
            Settlement settlement = Settlement.builder()
                    .scheduleId(event.scheduleId())
                    .sum(0L)  // 초기값 0, 참여자 증가 시 업데이트
                    .totalStatus(TotalStatus.HOLDING)
                    .receiver(leader)  // 리더가 receiver
                    .build();
            settlementRepository.save(settlement);

            log.info("[Event.Completed] type=ScheduleCreatedEvent, target=Settlement, scheduleId={}, settlementId={}",
                    event.scheduleId(), settlement.getSettlementId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleCreatedEvent, target=Settlement, scheduleId={}", event.scheduleId(), e);
            throw e;
        }
    }

    /**
     * 일정 참여 이벤트 처리
     * - UserSettlement 생성 (HOLD_ACTIVE 상태)
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleJoinedEvent(ScheduleJoinedEvent event) {
        log.info("[Event.Received] type=ScheduleJoinedEvent, target=Settlement, scheduleId={}, userId={}",
                event.scheduleId(), event.userId());

        try {
            Settlement settlement = settlementRepository.findByScheduleId(event.scheduleId())
                    .orElseThrow(() -> new IllegalArgumentException("Settlement not found for scheduleId: " + event.scheduleId()));

            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.userId()));

            // UserSettlement 생성 (HOLD_ACTIVE 상태)
            UserSettlement userSettlement = UserSettlement.builder()
                    .user(user)
                    .settlement(settlement)
                    .settlementStatus(SettlementStatus.HOLD_ACTIVE)
                    .build();
            userSettlementRepository.save(userSettlement);

            log.info("[Event.Completed] type=ScheduleJoinedEvent, target=Settlement, scheduleId={}, userId={}, userSettlementId={}",
                    event.scheduleId(), event.userId(), userSettlement.getUserSettlementId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleJoinedEvent, target=Settlement, scheduleId={}, userId={}", event.scheduleId(), event.userId(), e);
            throw e;
        }
    }

    /**
     * 일정 참여 취소 이벤트 처리
     * - UserSettlement 삭제
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleLeftEvent(ScheduleLeftEvent event) {
        log.info("[Event.Received] type=ScheduleLeftEvent, target=Settlement, scheduleId={}, userId={}",
                event.scheduleId(), event.userId());

        try {
            Settlement settlement = settlementRepository.findByScheduleId(event.scheduleId())
                    .orElseThrow(() -> new IllegalArgumentException("Settlement not found for scheduleId: " + event.scheduleId()));

            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.userId()));

            UserSettlement userSettlement = userSettlementRepository.findByUserAndSettlement(user, settlement)
                    .orElse(null);

            if (userSettlement != null) {
                userSettlementRepository.delete(userSettlement);
                log.info("[Event.Completed] type=ScheduleLeftEvent, target=Settlement, scheduleId={}, userId={}", event.scheduleId(), event.userId());
            } else {
                log.warn("UserSettlement not found: scheduleId={}, userId={}", event.scheduleId(), event.userId());
            }
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleLeftEvent, target=Settlement, scheduleId={}, userId={}", event.scheduleId(), event.userId(), e);
            throw e;
        }
    }

    /**
     * 일정 삭제 이벤트 처리
     * - Settlement 및 모든 UserSettlement 삭제
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleDeletedEvent(ScheduleDeletedEvent event) {
        log.info("[Event.Received] type=ScheduleDeletedEvent, target=Settlement, scheduleId={}", event.scheduleId());

        try {
            // Settlement 삭제 (cascade로 UserSettlement도 함께 삭제됨)
            settlementRepository.deleteByScheduleId(event.scheduleId());

            log.info("[Event.Completed] type=ScheduleDeletedEvent, target=Settlement, scheduleId={}", event.scheduleId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleDeletedEvent, target=Settlement, scheduleId={}", event.scheduleId(), e);
            throw e;
        }
    }
}
