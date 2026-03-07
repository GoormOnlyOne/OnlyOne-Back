package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.common.event.ScheduleCompletedEvent;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일정 배치 처리 서비스
 * - 만료 일정 상태 변경 (READY → ENDED)
 * - ScheduleCompletedEvent 발행 (Settlement 도메인 연동)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleBatchService {
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 스케줄 Status를 READY -> ENDED로 변경하는 스케줄링
     * 1) 만료 대상 조회 (이벤트 데이터 수집)
     * 2) 상태 일괄 변경
     * 3) ScheduleCompletedEvent 발행 (정산 도메인 연동)
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void updateScheduleStatus() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 만료 대상 스케줄 조회 (상태 변경 전, club JOIN FETCH)
        List<Schedule> expiredSchedules = scheduleRepository.findExpiredSchedules(
                ScheduleStatus.READY, now);

        // 2. 상태 일괄 변경
        int updatedCount = scheduleRepository.updateExpiredSchedules(
                ScheduleStatus.ENDED,
                ScheduleStatus.READY,
                now
        );
        log.info("[Schedule.StatusUpdate] batch READY->ENDED, count={}", updatedCount);

        // 3. 완료된 스케줄별 ScheduleCompletedEvent 발행
        for (Schedule schedule : expiredSchedules) {
            publishScheduleCompletedEvent(schedule, now);
        }
    }

    private void publishScheduleCompletedEvent(Schedule schedule, LocalDateTime completedAt) {
        try {
            User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(
                    schedule, ScheduleRole.LEADER).orElse(null);
            if (leader == null) {
                log.warn("[Schedule.StatusUpdate] leader not found, scheduleId={}", schedule.getScheduleId());
                return;
            }

            List<Long> participantUserIds = userScheduleRepository.findUsersBySchedule(schedule)
                    .stream().map(User::getUserId).toList();

            // 멤버 수 (리더 제외) × 비용 = 총 정산 금액
            long memberCount = participantUserIds.stream()
                    .filter(id -> !id.equals(leader.getUserId()))
                    .count();
            long totalCost = schedule.getCost() * memberCount;

            eventPublisher.publishEvent(new ScheduleCompletedEvent(
                    schedule.getScheduleId(),
                    schedule.getClub().getClubId(),
                    leader.getUserId(),
                    participantUserIds,
                    totalCost,
                    completedAt
            ));
            log.info("[Schedule.StatusUpdate] ScheduleCompletedEvent published, scheduleId={}, members={}",
                    schedule.getScheduleId(), memberCount);
        } catch (Exception e) {
            log.error("[Schedule.StatusUpdate] event publish failed, scheduleId={}",
                    schedule.getScheduleId(), e);
        }
    }
}
