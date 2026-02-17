package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("일정 참여 중복 방지 + Wallet Hold 통합 테스트")
class ScheduleJoinIntegrationTest {

    @InjectMocks
    private ScheduleService scheduleService;

    @Mock
    private UserScheduleRepository userScheduleRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ClubRepository clubRepository;
    @Mock
    private UserService userService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private UserClubRepository userClubRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private User member;
    private Club club;
    private Schedule schedule;
    private UserClub memberUserClub;

    @BeforeEach
    void setUp() {
        member = User.builder()
                .userId(2L)
                .kakaoId(2000L)
                .nickname("멤버")
                .birth(LocalDate.of(1996, 1, 1))
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .city("서울특별시")
                .district("강남구")
                .build();

        club = Club.builder()
                .clubId(1L)
                .name("테스트 모임")
                .userLimit(10)
                .description("테스트 설명")
                .city("서울특별시")
                .district("강남구")
                .build();

        schedule = Schedule.builder()
                .scheduleId(1L)
                .name("정기 모임")
                .location("구름스퀘어 강남")
                .cost(10000L)
                .userLimit(10)
                .scheduleTime(LocalDateTime.now().plusDays(7))
                .scheduleStatus(ScheduleStatus.READY)
                .club(club)
                .userSchedules(new ArrayList<>())
                .build();

        memberUserClub = UserClub.builder()
                .userClubId(2L)
                .user(member)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
    }

    @Test
    @DisplayName("애플리케이션 레벨 중복 참여 검사")
    void joinSchedule_applicationLevel_duplicatePrevented() {
        // given
        UserSchedule existing = UserSchedule.builder()
                .userScheduleId(1L)
                .user(member)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                .willReturn(Optional.of(existing));

        // when & then
        assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_SCHEDULE);

        verify(walletRepository, never()).holdBalanceIfEnough(anyLong(), anyLong());
    }

    @Test
    @DisplayName("DB 레벨 UniqueConstraint 위반 시 wallet hold 롤백")
    void joinSchedule_dbLevel_uniqueConstraintViolation_walletRolledBack() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                .willReturn(Optional.empty());
        given(userClubRepository.findByUserAndClub(member, club))
                .willReturn(Optional.of(memberUserClub));
        given(walletRepository.holdBalanceIfEnough(2L, 10000L)).willReturn(1);
        given(userScheduleRepository.save(any(UserSchedule.class))).willReturn(
                UserSchedule.builder().user(member).schedule(schedule).scheduleRole(ScheduleRole.MEMBER).build());
        doThrow(new DataIntegrityViolationException("Duplicate entry"))
                .when(userScheduleRepository).flush();

        // when & then
        assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_SCHEDULE);

        // wallet hold가 롤백되었는지 확인
        verify(walletRepository).releaseHoldBalance(2L, 10000L);
    }

    @Test
    @DisplayName("정상 참여: wallet hold 성공 후 일정 참여")
    void joinSchedule_success_walletHoldAndJoin() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                .willReturn(Optional.empty());
        given(userClubRepository.findByUserAndClub(member, club))
                .willReturn(Optional.of(memberUserClub));
        given(walletRepository.holdBalanceIfEnough(2L, 10000L)).willReturn(1);
        given(userScheduleRepository.save(any(UserSchedule.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        scheduleService.joinSchedule(1L, 1L);

        // then
        verify(walletRepository).holdBalanceIfEnough(2L, 10000L);
        verify(userScheduleRepository).save(any(UserSchedule.class));
    }

    @Test
    @DisplayName("잔액 부족: wallet hold 실패 시 참여 불가")
    void joinSchedule_insufficientBalance() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
        given(userService.getCurrentUser()).willReturn(member);
        given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
        given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                .willReturn(Optional.empty());
        given(userClubRepository.findByUserAndClub(member, club))
                .willReturn(Optional.of(memberUserClub));
        given(walletRepository.holdBalanceIfEnough(2L, 10000L)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);

        verify(userScheduleRepository, never()).save(any(UserSchedule.class));
    }
}
