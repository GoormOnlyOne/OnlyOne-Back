package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.common.event.ScheduleCompletedEvent;
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
import com.example.onlyone.domain.schedule.dto.response.ScheduleDetailResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleUserResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 일정(Schedule) 도메인 서비스
 * - 일정 생성 시 ScheduleCreatedEvent 발행 (Chat 도메인이 채팅방 생성)
 * - 일정 완료 시 ScheduleCompletedEvent 발행 (Settlement 도메인이 정산 생성)
 */
@Log4j2
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ScheduleService {
    private final UserScheduleRepository userScheduleRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClubRepository clubRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final UserClubRepository userClubRepository;
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

    /**
     * 정기 모임 생성
     * - 일정 생성 후 ScheduleCreatedEvent 발행
     * - Chat 도메인이 이벤트를 구독하여 채팅방 생성
     */
    @Transactional
    public ScheduleCreateResponseDto createSchedule(Long clubId, ScheduleRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

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

        // 이벤트 발행: Chat 도메인이 채팅방 생성
        eventPublisher.publishEvent(new ScheduleCreatedEvent(
                schedule.getScheduleId(),
                club.getClubId(),
                user.getUserId(),
                schedule.getName(),
                schedule.getScheduleTime()
        ));

        return new ScheduleCreateResponseDto(schedule.getScheduleId());
    }

    /**
     * 정기 모임 수정
     * TODO: 비용 변경 시 UserSettlement의 hold 금액 조정 필요
     * 현재는 Settlement 의존성 제거로 인해 비용 변경 로직 제외
     */
    @Transactional
    public void updateSchedule(Long clubId, Long scheduleId, ScheduleRequestDto requestDto) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));

        User user = userService.getCurrentUser();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        if (userSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }

        if (schedule.getScheduleStatus() != ScheduleStatus.READY) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        // TODO: 비용 변경 시 Settlement 이벤트 발행 필요
        // 현재는 비용 변경을 허용하지만, 참여자가 있는 경우 Settlement 조정이 필요함
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
        // JPA dirty checking: managed 엔티티는 트랜잭션 커밋 시 자동 flush
    }

    /**
     * 정기 모임 참여
     * TODO: UserSettlement 생성 및 wallet hold는 Settlement 도메인으로 이동 필요
     * 현재는 wallet hold만 수행
     */
    @Transactional
    public void joinSchedule(Long clubId, Long scheduleId) {
        // 비관적 락: 정원 체크와 참여 INSERT 사이의 레이스 컨디션 방지
        Schedule schedule = scheduleRepository.findByIdWithLock(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));

        User user = userService.getCurrentUser();

        int userCount = userScheduleRepository.countBySchedule(schedule);
        if (userCount >= schedule.getUserLimit()) {
            throw new CustomException(ErrorCode.ALREADY_EXCEEDED_SCHEDULE);
        }

        // 중복 참여 체크: unique 제약조건 + DataIntegrityViolationException catch로 처리
        // 별도 SELECT 쿼리 제거 (1 쿼리 절약)

        if (schedule.getScheduleStatus() != ScheduleStatus.READY ||
                schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        // EXISTS 서브쿼리 (엔티티 로딩 없이 존재 여부만 확인)
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), clubId)) {
            throw new CustomException(ErrorCode.USER_CLUB_NOT_FOUND);
        }

        // 잔액 체크 + wallet에 예약금 홀드
        int flag = walletRepository.holdBalanceIfEnough(user.getUserId(), schedule.getCost());
        if (flag == 0) {
            throw new CustomException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }

        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();
        try {
            userScheduleRepository.save(userSchedule);
            userScheduleRepository.flush();
        } catch (DataIntegrityViolationException e) {
            walletRepository.releaseHoldBalance(user.getUserId(), schedule.getCost());
            throw new CustomException(ErrorCode.ALREADY_JOINED_SCHEDULE);
        }

        // 이벤트 발행: Chat(UserChatRoom 추가), Settlement(UserSettlement 생성)
        eventPublisher.publishEvent(new ScheduleJoinedEvent(
                schedule.getScheduleId(),
                clubId,
                user.getUserId(),
                schedule.getCost()
        ));
    }

    /**
     * 정기 모임 참여 취소
     * 최적화: schedule + userSchedule 단일 쿼리 조회, club 별도 조회 제거
     */
    @Transactional
    public void leaveSchedule(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();

        // 단일 쿼리: userSchedule + schedule JOIN FETCH
        UserSchedule userSchedule = userScheduleRepository.findByUserAndScheduleIdWithSchedule(user, scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        Schedule schedule = userSchedule.getSchedule();

        // clubId 일치 검증 (별도 DB 조회 없이)
        if (!schedule.getClub().getClubId().equals(clubId)) {
            throw new CustomException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        if (schedule.getScheduleStatus() != ScheduleStatus.READY ||
                schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        if (userSchedule.getScheduleRole() == ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }

        // wallet hold 해제
        Long amount = schedule.getCost();
        int flag = walletRepository.releaseHoldBalance(user.getUserId(), amount);
        if (flag == 0) {
            throw new CustomException(ErrorCode.WALLET_HOLD_STATE_CONFLICT);
        }

        userScheduleRepository.delete(userSchedule);

        // 이벤트 발행: Chat(UserChatRoom 제거), Settlement(UserSettlement 제거)
        eventPublisher.publishEvent(new ScheduleLeftEvent(
                schedule.getScheduleId(),
                clubId,
                user.getUserId()
        ));
    }

    /**
     * 모임 스케줄 목록 조회
     * 최적화: 1+2N 쿼리 → 1 쿼리 (서브쿼리 + LEFT JOIN)
     */
    @Transactional(readOnly = true)
    public List<ScheduleResponseDto> getScheduleList(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        User currentUser = userService.getCurrentUser();

        return scheduleRepository.findScheduleListWithUserInfo(club, currentUser).stream()
                .map(row -> {
                    Schedule schedule = (Schedule) row[0];
                    int userCount = ((Long) row[1]).intValue();
                    ScheduleRole currentUserRole = (ScheduleRole) row[2]; // null if not joined
                    boolean isJoined = currentUserRole != null;
                    boolean isLeader = currentUserRole == ScheduleRole.LEADER;
                    long dDay = ChronoUnit.DAYS.between(LocalDate.now(),
                            schedule.getScheduleTime().toLocalDate());
                    return ScheduleResponseDto.from(schedule, userCount, isJoined, isLeader, dDay);
                })
                .collect(Collectors.toList());
    }

    /**
     * 모임 스케줄 참여자 목록 조회
     */
    @Transactional(readOnly = true)
    public List<ScheduleUserResponseDto> getScheduleUserList(Long clubId, Long scheduleId) {
        // 단일 쿼리: club 검증 + schedule 검증 + user 조회 (3쿼리 → 1쿼리)
        List<User> users = userScheduleRepository.findUsersByScheduleIdAndClubId(scheduleId, clubId);
        return users.stream()
                .map(ScheduleUserResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 스케줄 정보 상세 조회
     */
    @Transactional(readOnly = true)
    public ScheduleDetailResponseDto getScheduleDetails(Long clubId, Long scheduleId) {
        // 단일 쿼리: club 검증 + schedule 조회 (2쿼리 → 1쿼리)
        Schedule schedule = scheduleRepository.findByIdAndClubId(scheduleId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));

        return ScheduleDetailResponseDto.from(schedule);
    }

    /**
     * 정기 모임 삭제
     * TODO: ChatRoom 삭제는 Chat 도메인으로 이동 필요 (ScheduleDeletedEvent)
     */
    @Transactional
    public void deleteSchedule(Long clubId, Long scheduleId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));

        if (schedule.getScheduleStatus() != ScheduleStatus.READY ||
                schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.INVALID_SCHEDULE_DELETE);
        }

        User user = userService.getCurrentUser();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));

        if (userSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_DELETE_SCHEDULE);
        }

        // 참여자들의 wallet hold 배치 해제 (Lazy loading 없이 userId 직접 조회)
        List<Long> memberUserIds = userScheduleRepository.findMemberUserIdsByScheduleAndRole(
                schedule, ScheduleRole.MEMBER);
        if (!memberUserIds.isEmpty()) {
            walletRepository.batchReleaseHoldBalance(memberUserIds, schedule.getCost());
        }

        scheduleRepository.delete(schedule);

        // 이벤트 발행: Chat(채팅방 삭제), Settlement(정산 삭제)
        eventPublisher.publishEvent(new ScheduleDeletedEvent(
                scheduleId,
                club.getClubId()
        ));
    }
}
