package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleCreateResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.service.WalletHoldService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 일정(Schedule) 커맨드 서비스 — 생성·수정·참여·탈퇴·삭제
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ScheduleCommandService {

    private final UserScheduleRepository userScheduleRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClubRepository clubRepository;
    private final UserService userService;
    private final WalletHoldService walletHoldService;
    private final UserClubRepository userClubRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 정기 모임 생성 */
    public ScheduleCreateResponseDto createSchedule(Long clubId, ScheduleRequestDto requestDto) {
        Club club = findClubOrThrow(clubId);

        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));

        if (userClub.getClubRole() != ClubRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_CREATE_SCHEDULE);
        }

        Schedule schedule = requestDto.toEntity(club);
        scheduleRepository.save(schedule);

        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.LEADER)
                .build();
        userScheduleRepository.save(userSchedule);

        eventPublisher.publishEvent(new ScheduleCreatedEvent(
                schedule.getScheduleId(),
                club.getClubId(),
                user.getUserId(),
                schedule.getName(),
                schedule.getScheduleTime()
        ));

        log.info("일정 생성: scheduleId={}, clubId={}, userId={}", schedule.getScheduleId(), clubId, user.getUserId());
        return new ScheduleCreateResponseDto(schedule.getScheduleId());
    }

    /** 정기 모임 수정 */
    public void updateSchedule(Long clubId, Long scheduleId, ScheduleRequestDto requestDto) {
        findClubOrThrow(clubId);

        Schedule schedule = findScheduleOrThrow(scheduleId);
        validateScheduleBelongsToClub(schedule, clubId);

        User user = userService.getCurrentUser();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        if (userSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }

        if (schedule.isNotModifiable()) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        if (!schedule.getCost().equals(requestDto.cost())) {
            int participantCount = userScheduleRepository.countBySchedule(schedule);
            if (participantCount > 1) {
                log.warn("참여자가 있는 일정의 비용 변경은 지원하지 않습니다. scheduleId={}, participants={}",
                         scheduleId, participantCount);
                throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
            }
        }

        schedule.update(requestDto.name(), requestDto.location(),
                requestDto.cost(), requestDto.userLimit(), requestDto.scheduleTime());
    }

    /** 정기 모임 참여 */
    public void joinSchedule(Long clubId, Long scheduleId) {
        Schedule schedule = scheduleRepository.findByIdWithLock(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        validateScheduleBelongsToClub(schedule, clubId);

        User user = userService.getCurrentUser();

        int userCount = userScheduleRepository.countBySchedule(schedule);
        if (userCount >= schedule.getUserLimit()) {
            throw new CustomException(ErrorCode.ALREADY_EXCEEDED_SCHEDULE);
        }

        if (schedule.isNotModifiable()) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), clubId)) {
            throw new CustomException(ErrorCode.USER_CLUB_NOT_FOUND);
        }

        walletHoldService.holdOrThrow(user.getUserId(), schedule.getCost());

        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();
        try {
            userScheduleRepository.save(userSchedule);
            userScheduleRepository.flush();
        } catch (DataIntegrityViolationException e) {
            walletHoldService.releaseOrThrow(user.getUserId(), schedule.getCost());
            throw new CustomException(ErrorCode.ALREADY_JOINED_SCHEDULE);
        }

        eventPublisher.publishEvent(new ScheduleJoinedEvent(
                schedule.getScheduleId(),
                clubId,
                user.getUserId(),
                schedule.getCost()
        ));
        log.info("일정 참여: scheduleId={}, userId={}", scheduleId, user.getUserId());
    }

    /** 정기 모임 참여 취소 */
    public void leaveSchedule(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();

        UserSchedule userSchedule = userScheduleRepository.findByUserAndScheduleIdWithSchedule(user, scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        Schedule schedule = userSchedule.getSchedule();

        if (!schedule.getClub().getClubId().equals(clubId)) {
            throw new CustomException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        if (schedule.isNotModifiable()) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        if (userSchedule.getScheduleRole() == ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }

        walletHoldService.releaseOrThrow(user.getUserId(), schedule.getCost());

        userScheduleRepository.delete(userSchedule);

        eventPublisher.publishEvent(new ScheduleLeftEvent(
                schedule.getScheduleId(),
                clubId,
                user.getUserId()
        ));
        log.info("일정 탈퇴: scheduleId={}, userId={}", scheduleId, user.getUserId());
    }

    /** 정기 모임 삭제 */
    public void deleteSchedule(Long clubId, Long scheduleId) {
        Club club = findClubOrThrow(clubId);

        Schedule schedule = findScheduleOrThrow(scheduleId);
        validateScheduleBelongsToClub(schedule, clubId);

        if (schedule.isNotModifiable()) {
            throw new CustomException(ErrorCode.INVALID_SCHEDULE_DELETE);
        }

        User user = userService.getCurrentUser();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        if (userSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_DELETE_SCHEDULE);
        }

        List<Long> memberUserIds = userScheduleRepository.findMemberUserIdsByScheduleAndRole(
                schedule, ScheduleRole.MEMBER);
        walletHoldService.batchRelease(memberUserIds, schedule.getCost());

        scheduleRepository.delete(schedule);

        eventPublisher.publishEvent(new ScheduleDeletedEvent(
                scheduleId,
                club.getClubId()
        ));
        log.info("일정 삭제: scheduleId={}, clubId={}", scheduleId, clubId);
    }

    private Club findClubOrThrow(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
    }

    private Schedule findScheduleOrThrow(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
    }

    private void validateScheduleBelongsToClub(Schedule schedule, Long clubId) {
        if (!schedule.getClub().getClubId().equals(clubId)) {
            throw new CustomException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
    }
}
