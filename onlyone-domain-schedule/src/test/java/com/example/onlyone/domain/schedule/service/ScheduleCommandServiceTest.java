package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.schedule.fixture.ScheduleFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleCommandService 단위 테스트")
class ScheduleCommandServiceTest {

    @InjectMocks private ScheduleCommandService scheduleCommandService;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserService userService;
    @Mock private WalletHoldService walletHoldService;
    @Mock private UserClubRepository userClubRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private User leader;
    private User member;
    private Club club;
    private Schedule schedule;
    private UserClub leaderUC;
    private UserClub memberUC;
    private UserSchedule leaderUS;
    private UserSchedule memberUS;

    @BeforeEach
    void setUp() {
        leader = leader();
        member = member();
        club = club();
        schedule = schedule(club);
        leaderUC = leaderUserClub(leader, club);
        memberUC = memberUserClub(member, club);
        leaderUS = leaderUserSchedule(leader, schedule);
        memberUS = memberUserSchedule(member, schedule);
    }

    // =====================================================================
    // 정기모임 생성
    // =====================================================================
    @Nested
    @DisplayName("정기모임 생성")
    class CreateSchedule {

        @Test
        @DisplayName("성공: 리더가 정기모임을 생성하고 이벤트가 발행된다")
        void 리더가_정기모임을_생성하고_이벤트가_발행된다() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userClubRepository.findByUserAndClub(leader, club)).willReturn(Optional.of(leaderUC));
            given(scheduleRepository.save(any(Schedule.class))).willAnswer(inv -> inv.getArgument(0));
            given(userScheduleRepository.save(any(UserSchedule.class))).willAnswer(inv -> inv.getArgument(0));

            ScheduleCreateResponseDto result = scheduleCommandService.createSchedule(1L, requestDto());

            then(scheduleRepository).should().save(any(Schedule.class));
            then(userScheduleRepository).should().save(any(UserSchedule.class));

            ArgumentCaptor<ScheduleCreatedEvent> captor = ArgumentCaptor.forClass(ScheduleCreatedEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());

            ScheduleCreatedEvent event = captor.getValue();
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.leaderUserId()).isEqualTo(1L);
            assertThat(event.scheduleName()).isEqualTo("정기 모임");
        }

        @Test
        @DisplayName("실패: 모임이 존재하지 않으면 CLUB_NOT_FOUND")
        void 모임이_존재하지_않으면_CLUB_NOT_FOUND() {
            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleCommandService.createSchedule(999L, requestDto()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_CREATE_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_CREATE_SCHEDULE() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(member);
            given(userClubRepository.findByUserAndClub(member, club)).willReturn(Optional.of(memberUC));

            assertThatThrownBy(() -> scheduleCommandService.createSchedule(1L, requestDto()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_CREATE_SCHEDULE);
        }
    }

    // =====================================================================
    // 정기모임 수정
    // =====================================================================
    @Nested
    @DisplayName("정기모임 수정")
    class UpdateSchedule {

        @Test
        @DisplayName("성공: 리더가 정기모임을 수정한다")
        void 리더가_정기모임을_수정한다() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUS));

            scheduleCommandService.updateSchedule(1L, 1L, updateRequestDto());

            assertThat(schedule.getName()).isEqualTo("수정된 정기 모임");
            assertThat(schedule.getLocation()).isEqualTo("역삼역");
            assertThat(schedule.getUserLimit()).isEqualTo(20);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_MODIFY_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_MODIFY_SCHEDULE() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                    .willReturn(Optional.of(memberUS));

            assertThatThrownBy(() -> scheduleCommandService.updateSchedule(1L, 1L, updateRequestDto()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 이미 종료된 스케줄이면 ALREADY_ENDED_SCHEDULE")
        void 이미_종료된_스케줄이면_ALREADY_ENDED_SCHEDULE() {
            Schedule ended = endedSchedule(1L, club);
            UserSchedule leaderOfEnded = leaderUserSchedule(leader, ended);

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(ended));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, ended))
                    .willReturn(Optional.of(leaderOfEnded));

            assertThatThrownBy(() -> scheduleCommandService.updateSchedule(1L, 1L, updateRequestDto()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 참여자가 있는 상태에서 비용 변경 불가")
        void 참여자가_있는_상태에서_비용_변경_불가() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUS));
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(2);

            assertThatThrownBy(() -> scheduleCommandService.updateSchedule(1L, 1L, costChangeRequestDto(20000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }
    }

    // =====================================================================
    // 정기모임 참여
    // =====================================================================
    @Nested
    @DisplayName("정기모임 참여")
    class JoinSchedule {

        @Test
        @DisplayName("성공: 멤버가 정기모임에 참여하고 지갑 홀드된다")
        void 멤버가_정기모임에_참여하고_지갑_홀드된다() {
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
            given(userScheduleRepository.save(any(UserSchedule.class))).willAnswer(inv -> inv.getArgument(0));

            scheduleCommandService.joinSchedule(1L, 1L);

            then(walletHoldService).should().holdOrThrow(2L, 10000L);
            then(userScheduleRepository).should().save(any(UserSchedule.class));

            ArgumentCaptor<ScheduleJoinedEvent> captor = ArgumentCaptor.forClass(ScheduleJoinedEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());

            ScheduleJoinedEvent event = captor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.userId()).isEqualTo(2L);
            assertThat(event.cost()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("실패: 정원 초과 ALREADY_EXCEEDED_SCHEDULE")
        void 정원_초과_ALREADY_EXCEEDED_SCHEDULE() {
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(10);

            assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_EXCEEDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 종료된 스케줄 ALREADY_ENDED_SCHEDULE")
        void 종료된_스케줄_ALREADY_ENDED_SCHEDULE() {
            Schedule ended = endedSchedule(1L, club);
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(ended));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(ended)).willReturn(1);

            assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 모임 미가입 USER_CLUB_NOT_FOUND")
        void 모임_미가입_USER_CLUB_NOT_FOUND() {
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(false);

            assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 잔액 부족 WALLET_BALANCE_NOT_ENOUGH")
        void 잔액_부족_WALLET_BALANCE_NOT_ENOUGH() {
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
            willThrow(new CustomException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH))
                    .given(walletHoldService).holdOrThrow(2L, 10000L);

            assertThatThrownBy(() -> scheduleCommandService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }
    }

    // =====================================================================
    // 정기모임 탈퇴
    // =====================================================================
    @Nested
    @DisplayName("정기모임 탈퇴")
    class LeaveSchedule {

        @Test
        @DisplayName("성공: 멤버가 탈퇴하고 홀드가 해제된다")
        void 멤버가_탈퇴하고_홀드가_해제된다() {
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberUS));

            scheduleCommandService.leaveSchedule(1L, 1L);

            then(walletHoldService).should().releaseOrThrow(2L, 10000L);
            then(userScheduleRepository).should().delete(memberUS);

            ArgumentCaptor<ScheduleLeftEvent> captor = ArgumentCaptor.forClass(ScheduleLeftEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());

            ScheduleLeftEvent event = captor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.userId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("실패: 종료된 스케줄 ALREADY_ENDED_SCHEDULE")
        void 종료된_스케줄_ALREADY_ENDED_SCHEDULE() {
            Schedule ended = endedSchedule(1L, club);
            UserSchedule memberOfEnded = memberUserSchedule(member, ended);

            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberOfEnded));

            assertThatThrownBy(() -> scheduleCommandService.leaveSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 리더는 탈퇴 불가 LEADER_CANNOT_LEAVE_SCHEDULE")
        void 리더는_탈퇴_불가_LEADER_CANNOT_LEAVE_SCHEDULE() {
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(leader, 1L))
                    .willReturn(Optional.of(leaderUS));

            assertThatThrownBy(() -> scheduleCommandService.leaveSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 홀드 해제 실패 WALLET_HOLD_STATE_CONFLICT")
        void 홀드_해제_실패_WALLET_HOLD_STATE_CONFLICT() {
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberUS));
            willThrow(new CustomException(ErrorCode.WALLET_HOLD_STATE_CONFLICT))
                    .given(walletHoldService).releaseOrThrow(2L, 10000L);

            assertThatThrownBy(() -> scheduleCommandService.leaveSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.WALLET_HOLD_STATE_CONFLICT);
        }
    }

    // =====================================================================
    // 정기모임 삭제
    // =====================================================================
    @Nested
    @DisplayName("정기모임 삭제")
    class DeleteSchedule {

        @Test
        @DisplayName("성공: 리더가 삭제하고 참여자 홀드가 배치 해제된다")
        void 리더가_삭제하고_참여자_홀드가_배치_해제된다() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUS));
            given(userScheduleRepository.findMemberUserIdsByScheduleAndRole(schedule, ScheduleRole.MEMBER))
                    .willReturn(List.of(2L, 3L));

            scheduleCommandService.deleteSchedule(1L, 1L);

            then(walletHoldService).should().batchRelease(List.of(2L, 3L), 10000L);
            then(scheduleRepository).should().delete(schedule);

            ArgumentCaptor<ScheduleDeletedEvent> captor = ArgumentCaptor.forClass(ScheduleDeletedEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());

            ScheduleDeletedEvent event = captor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("실패: 시작된 스케줄은 삭제 불가 INVALID_SCHEDULE_DELETE")
        void 시작된_스케줄은_삭제_불가_INVALID_SCHEDULE_DELETE() {
            Schedule ended = endedSchedule(1L, club);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(ended));

            assertThatThrownBy(() -> scheduleCommandService.deleteSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_SCHEDULE_DELETE);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_DELETE_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_DELETE_SCHEDULE() {
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                    .willReturn(Optional.of(memberUS));

            assertThatThrownBy(() -> scheduleCommandService.deleteSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_DELETE_SCHEDULE);
        }
    }
}
