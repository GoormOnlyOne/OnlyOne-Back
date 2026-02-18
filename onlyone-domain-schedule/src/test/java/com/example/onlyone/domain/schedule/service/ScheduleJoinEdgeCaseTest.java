package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.service.WalletHoldService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static com.example.onlyone.domain.schedule.fixture.ScheduleFixtures.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("일정 참여 중복 방지 + Wallet Hold 엣지케이스 테스트")
class ScheduleJoinEdgeCaseTest {

    @InjectMocks private ScheduleCommandService scheduleCommandService;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserService userService;
    @Mock private WalletHoldService walletHoldService;
    @Mock private UserClubRepository userClubRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private User member;
    private Club club;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        member = member();
        club = club();
        schedule = schedule(club);
    }

    @Test
    @DisplayName("DB 레벨 UniqueConstraint 위반 시 wallet hold 롤백")
    void dbLevel_uniqueConstraintViolation_walletRolledBack() {
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
        given(userScheduleRepository.save(any(UserSchedule.class))).willReturn(
                memberUserSchedule(member, schedule));
        willThrow(new DataIntegrityViolationException("Duplicate entry"))
                .given(userScheduleRepository).flush();

        assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_SCHEDULE);

        then(walletHoldService).should().releaseOrThrow(2L, 10000L);
    }

    @Test
    @DisplayName("정상 참여: wallet hold 성공 후 일정 참여")
    void success_walletHoldAndJoin() {
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
        given(userScheduleRepository.save(any(UserSchedule.class))).willAnswer(inv -> inv.getArgument(0));

        scheduleCommandService.joinSchedule(1L, 1L);

        then(walletHoldService).should().holdOrThrow(2L, 10000L);
        then(userScheduleRepository).should().save(any(UserSchedule.class));
    }

    @Test
    @DisplayName("잔액 부족: wallet hold 실패 시 참여 불가")
    void insufficientBalance() {
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
        willThrow(new CustomException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH))
                .given(walletHoldService).holdOrThrow(2L, 10000L);

        assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);

        then(userScheduleRepository).should(never()).save(any(UserSchedule.class));
    }
}
