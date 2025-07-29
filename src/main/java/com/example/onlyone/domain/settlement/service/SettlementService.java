package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementService {
    private final UserService userService;
    private final ClubRepository clubRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;

    /* 정산 요청 생성 */
    public void createSettlement(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        // 종료된 스케줄인지 확인
        if (schedule.getScheduleStatus() == ScheduleStatus.ENDED || schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.BEFORE_SCHEDULE_START);
        }
        // 정산 금액이 0원이면 즉시 스케줄 종료
        if (schedule.getCost() == 0) {
            schedule.updateStatus(ScheduleStatus.CLOSED);
        }
        UserSchedule leaderUserSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        // 리더가 호출하고 있는지 확인
        if (leaderUserSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_CREATE_SETTLEMENT);
        }
        int userCount = userScheduleRepository.countBySchedule(schedule);
        // 스케줄 참여 인원이 1명(리더)면 즉시 스케줄 종료
        if (userCount <= 1) {
            schedule.updateStatus(ScheduleStatus.CLOSED);
        }
        int totalAmount = userCount * schedule.getCost();
        Settlement settlement = Settlement.builder()
                .schedule(schedule)
                .sum(totalAmount)
                .totalStatus(TotalStatus.REQUESTED)
                .receiver(user)
                .build();
        settlementRepository.save(settlement);
        List<UserSchedule> userSchedules = userScheduleRepository.findUserSchedulesBySchedule(schedule);
        userSchedules.remove(leaderUserSchedule);
        List<UserSettlement> userSettlements = userSchedules.stream()
                .map(userSchedule -> UserSettlement.builder()
                        .user(userSchedule.getUser())
                        .settlement(settlement)
                        .settlementStatus(SettlementStatus.REQUESTED)
                        .build())
                .toList();
        userSettlementRepository.saveAll(userSettlements);
    }
}
