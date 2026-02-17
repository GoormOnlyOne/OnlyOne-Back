package com.example.onlyone.domain.schedule.event;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleSettlementEventListener {

    private final ScheduleRepository scheduleRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSettlementCompleted(SettlementCompletedEvent event) {
        Schedule schedule = scheduleRepository.findById(event.scheduleId())
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        schedule.updateStatus(ScheduleStatus.CLOSED);
        scheduleRepository.save(schedule);
        log.info("Schedule {} closed after settlement {} completed",
                event.scheduleId(), event.settlementId());
    }
}
