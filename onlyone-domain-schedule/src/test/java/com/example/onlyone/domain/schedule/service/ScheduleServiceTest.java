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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService 단위 테스트")
class ScheduleServiceTest {

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

    private User leader;
    private User member;
    private Club club;
    private Schedule schedule;
    private UserClub leaderUserClub;
    private UserClub memberUserClub;
    private UserSchedule leaderUserSchedule;
    private UserSchedule memberUserSchedule;
    private ScheduleRequestDto requestDto;
    private LocalDateTime futureTime;

    @BeforeEach
    void setUp() {
        futureTime = LocalDateTime.now().plusDays(7);

        leader = User.builder()
                .userId(1L)
                .kakaoId(1000L)
                .nickname("리더")
                .birth(LocalDate.of(1995, 1, 1))
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .city("서울특별시")
                .district("강남구")
                .build();

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
                .scheduleTime(futureTime)
                .scheduleStatus(ScheduleStatus.READY)
                .club(club)
                .userSchedules(new ArrayList<>())
                .build();

        leaderUserClub = UserClub.builder()
                .userClubId(1L)
                .user(leader)
                .club(club)
                .clubRole(ClubRole.LEADER)
                .build();

        memberUserClub = UserClub.builder()
                .userClubId(2L)
                .user(member)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();

        leaderUserSchedule = UserSchedule.builder()
                .userScheduleId(1L)
                .user(leader)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.LEADER)
                .build();

        memberUserSchedule = UserSchedule.builder()
                .userScheduleId(2L)
                .user(member)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();

        requestDto = new ScheduleRequestDto(
                "정기 모임",
                "구름스퀘어 강남",
                10000L,
                10,
                futureTime
        );
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
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userClubRepository.findByUserAndClub(leader, club)).willReturn(Optional.of(leaderUserClub));
            given(scheduleRepository.save(any(Schedule.class))).willAnswer(invocation -> {
                Schedule saved = invocation.getArgument(0);
                return saved;
            });
            given(userScheduleRepository.save(any(UserSchedule.class))).willAnswer(invocation -> {
                UserSchedule saved = invocation.getArgument(0);
                return saved;
            });

            // when
            ScheduleCreateResponseDto result = scheduleService.createSchedule(1L, requestDto);

            // then
            verify(scheduleRepository).save(any(Schedule.class));
            verify(userScheduleRepository).save(any(UserSchedule.class));

            ArgumentCaptor<ScheduleCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ScheduleCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScheduleCreatedEvent event = eventCaptor.getValue();
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.leaderUserId()).isEqualTo(1L);
            assertThat(event.scheduleName()).isEqualTo("정기 모임");
        }

        @Test
        @DisplayName("실패: 모임이 존재하지 않으면 CLUB_NOT_FOUND")
        void 모임이_존재하지_않으면_CLUB_NOT_FOUND() {
            // given
            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_CREATE_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_CREATE_SCHEDULE() {
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(member);
            given(userClubRepository.findByUserAndClub(member, club)).willReturn(Optional.of(memberUserClub));

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(1L, requestDto))
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

        private ScheduleRequestDto updateRequestDto;

        @BeforeEach
        void setUp() {
            updateRequestDto = new ScheduleRequestDto(
                    "수정된 정기 모임",
                    "역삼역",
                    10000L,
                    20,
                    futureTime.plusDays(1)
            );
        }

        @Test
        @DisplayName("성공: 리더가 정기모임을 수정한다")
        void 리더가_정기모임을_수정한다() {
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUserSchedule));

            // when
            scheduleService.updateSchedule(1L, 1L, updateRequestDto);

            // then
            assertThat(schedule.getName()).isEqualTo("수정된 정기 모임");
            assertThat(schedule.getLocation()).isEqualTo("역삼역");
            assertThat(schedule.getUserLimit()).isEqualTo(20);
            // JPA dirty checking: managed 엔티티는 save() 호출 불필요
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_MODIFY_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_MODIFY_SCHEDULE() {
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                    .willReturn(Optional.of(memberUserSchedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.updateSchedule(1L, 1L, updateRequestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 이미 종료된 스케줄이면 ALREADY_ENDED_SCHEDULE")
        void 이미_종료된_스케줄이면_ALREADY_ENDED_SCHEDULE() {
            // given
            schedule.updateStatus(ScheduleStatus.ENDED);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUserSchedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.updateSchedule(1L, 1L, updateRequestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 참여자가 있는 상태에서 비용 변경 불가")
        void 참여자가_있는_상태에서_비용_변경_불가() {
            // given
            ScheduleRequestDto costChangeDto = new ScheduleRequestDto(
                    "정기 모임",
                    "구름스퀘어 강남",
                    20000L,  // 기존 10000 -> 20000으로 변경
                    10,
                    futureTime
            );

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUserSchedule));
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(2); // 리더 + 참여자 1명

            // when & then
            assertThatThrownBy(() -> scheduleService.updateSchedule(1L, 1L, costChangeDto))
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
            // given
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1); // 리더만 참여중
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
            given(walletRepository.holdBalanceIfEnough(2L, 10000L)).willReturn(1);
            given(userScheduleRepository.save(any(UserSchedule.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            scheduleService.joinSchedule(1L, 1L);

            // then
            verify(walletRepository).holdBalanceIfEnough(2L, 10000L);
            verify(userScheduleRepository).save(any(UserSchedule.class));

            ArgumentCaptor<ScheduleJoinedEvent> eventCaptor = ArgumentCaptor.forClass(ScheduleJoinedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScheduleJoinedEvent event = eventCaptor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.userId()).isEqualTo(2L);
            assertThat(event.cost()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("실패: 정원 초과 ALREADY_EXCEEDED_SCHEDULE")
        void 정원_초과_ALREADY_EXCEEDED_SCHEDULE() {
            // given
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(10); // 정원(10) 도달

            // when & then
            assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_EXCEEDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 종료된 스케줄 ALREADY_ENDED_SCHEDULE")
        void 종료된_스케줄_ALREADY_ENDED_SCHEDULE() {
            // given
            schedule.updateStatus(ScheduleStatus.ENDED);
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);

            // when & then
            assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 모임 미가입 USER_CLUB_NOT_FOUND")
        void 모임_미가입_USER_CLUB_NOT_FOUND() {
            // given
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 잔액 부족 WALLET_BALANCE_NOT_ENOUGH")
        void 잔액_부족_WALLET_BALANCE_NOT_ENOUGH() {
            // given
            given(scheduleRepository.findByIdWithLock(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.countBySchedule(schedule)).willReturn(1);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L)).willReturn(true);
            given(walletRepository.holdBalanceIfEnough(2L, 10000L)).willReturn(0);

            // when & then
            assertThatThrownBy(() -> scheduleService.joinSchedule(1L, 1L))
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
            // given
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberUserSchedule));
            given(walletRepository.releaseHoldBalance(2L, 10000L)).willReturn(1);

            // when
            scheduleService.leaveSchedule(1L, 1L);

            // then
            verify(walletRepository).releaseHoldBalance(2L, 10000L);
            verify(userScheduleRepository).delete(memberUserSchedule);

            ArgumentCaptor<ScheduleLeftEvent> eventCaptor = ArgumentCaptor.forClass(ScheduleLeftEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScheduleLeftEvent event = eventCaptor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.userId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("실패: 종료된 스케줄 ALREADY_ENDED_SCHEDULE")
        void 종료된_스케줄_ALREADY_ENDED_SCHEDULE() {
            // given
            schedule.updateStatus(ScheduleStatus.ENDED);
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberUserSchedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.leaveSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 리더는 탈퇴 불가 LEADER_CANNOT_LEAVE_SCHEDULE")
        void 리더는_탈퇴_불가_LEADER_CANNOT_LEAVE_SCHEDULE() {
            // given
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(leader, 1L))
                    .willReturn(Optional.of(leaderUserSchedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.leaveSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }

        @Test
        @DisplayName("실패: 홀드 해제 실패 WALLET_HOLD_STATE_CONFLICT")
        void 홀드_해제_실패_WALLET_HOLD_STATE_CONFLICT() {
            // given
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndScheduleIdWithSchedule(member, 1L))
                    .willReturn(Optional.of(memberUserSchedule));
            given(walletRepository.releaseHoldBalance(2L, 10000L)).willReturn(0);

            // when & then
            assertThatThrownBy(() -> scheduleService.leaveSchedule(1L, 1L))
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
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(leader);
            given(userScheduleRepository.findByUserAndSchedule(leader, schedule))
                    .willReturn(Optional.of(leaderUserSchedule));
            given(userScheduleRepository.findMemberUserIdsByScheduleAndRole(schedule, ScheduleRole.MEMBER))
                    .willReturn(List.of(2L, 3L));

            // when
            scheduleService.deleteSchedule(1L, 1L);

            // then
            verify(walletRepository).batchReleaseHoldBalance(List.of(2L, 3L), 10000L);
            verify(scheduleRepository).delete(schedule);

            ArgumentCaptor<ScheduleDeletedEvent> eventCaptor = ArgumentCaptor.forClass(ScheduleDeletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScheduleDeletedEvent event = eventCaptor.getValue();
            assertThat(event.scheduleId()).isEqualTo(1L);
            assertThat(event.clubId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("실패: 시작된 스케줄은 삭제 불가 INVALID_SCHEDULE_DELETE")
        void 시작된_스케줄은_삭제_불가_INVALID_SCHEDULE_DELETE() {
            // given
            schedule.updateStatus(ScheduleStatus.ENDED);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.deleteSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_SCHEDULE_DELETE);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_DELETE_SCHEDULE")
        void 리더가_아니면_MEMBER_CANNOT_DELETE_SCHEDULE() {
            // given
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(userService.getCurrentUser()).willReturn(member);
            given(userScheduleRepository.findByUserAndSchedule(member, schedule))
                    .willReturn(Optional.of(memberUserSchedule));

            // when & then
            assertThatThrownBy(() -> scheduleService.deleteSchedule(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_DELETE_SCHEDULE);
        }
    }

    // =====================================================================
    // 정기모임 목록 조회
    // =====================================================================
    @Nested
    @DisplayName("정기모임 목록 조회")
    class GetScheduleList {

        @Test
        @DisplayName("성공: 모임의 스케줄 목록 반환")
        void 모임의_스케줄_목록_반환() {
            // given
            Schedule schedule2 = Schedule.builder()
                    .scheduleId(2L)
                    .name("정기 모임 2")
                    .location("역삼역")
                    .cost(5000L)
                    .userLimit(5)
                    .scheduleTime(futureTime.plusDays(1))
                    .scheduleStatus(ScheduleStatus.READY)
                    .club(club)
                    .userSchedules(new ArrayList<>())
                    .build();

            // 단일 쿼리: [Schedule, userCount(Long), currentUserRole(ScheduleRole)]
            List<Object[]> queryResult = List.of(
                    new Object[]{schedule2, 0L, null},              // schedule2: 미참여
                    new Object[]{schedule, 1L, ScheduleRole.LEADER}  // schedule: 리더로 참여
            );

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(leader);
            given(scheduleRepository.findScheduleListWithUserInfo(club, leader))
                    .willReturn(queryResult);

            // when
            List<ScheduleResponseDto> result = scheduleService.getScheduleList(1L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("정기 모임 2");
            assertThat(result.get(1).name()).isEqualTo("정기 모임");
            assertThat(result.get(1).isJoined()).isTrue();
            assertThat(result.get(1).isLeader()).isTrue();
            assertThat(result.get(0).isJoined()).isFalse();
        }
    }

    // =====================================================================
    // 정기모임 상세 조회
    // =====================================================================
    @Nested
    @DisplayName("정기모임 상세 조회")
    class GetScheduleDetails {

        @Test
        @DisplayName("성공: 스케줄 상세 정보를 반환한다")
        void 스케줄_상세_정보를_반환한다() {
            // given
            given(scheduleRepository.findByIdAndClubId(1L, 1L)).willReturn(Optional.of(schedule));

            // when
            ScheduleDetailResponseDto result = scheduleService.getScheduleDetails(1L, 1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.scheduleId()).isEqualTo(1L);
            assertThat(result.name()).isEqualTo("정기 모임");
            assertThat(result.location()).isEqualTo("구름스퀘어 강남");
            assertThat(result.cost()).isEqualTo(10000L);
            assertThat(result.userLimit()).isEqualTo(10);
            assertThat(result.scheduleTime()).isEqualTo(futureTime);
        }
    }

    // =====================================================================
    // 자정 배치: updateScheduleStatus
    // =====================================================================
    @Nested
    @DisplayName("자정 배치 스케줄 상태 업데이트")
    class UpdateScheduleStatus {

        @Test
        @DisplayName("성공: 만료된 스케줄 READY→ENDED 전환 + ScheduleCompletedEvent 발행")
        void 만료된_스케줄_상태_전환_및_이벤트_발행() {
            // given
            Schedule expired = Schedule.builder()
                    .scheduleId(10L)
                    .name("만료된 모임")
                    .location("서울")
                    .cost(5000L)
                    .userLimit(10)
                    .scheduleTime(LocalDateTime.now().minusDays(1))
                    .scheduleStatus(ScheduleStatus.READY)
                    .club(club)
                    .build();

            given(scheduleRepository.findExpiredSchedules(eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(List.of(expired));
            given(scheduleRepository.updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(1);
            given(userScheduleRepository.findLeaderByScheduleAndScheduleRole(expired, ScheduleRole.LEADER))
                    .willReturn(Optional.of(leader));
            given(userScheduleRepository.findUsersBySchedule(expired))
                    .willReturn(List.of(leader, member));

            // when
            scheduleService.updateScheduleStatus();

            // then
            verify(scheduleRepository).updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class));

            ArgumentCaptor<ScheduleCompletedEvent> eventCaptor = ArgumentCaptor.forClass(ScheduleCompletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScheduleCompletedEvent event = eventCaptor.getValue();
            assertThat(event.scheduleId()).isEqualTo(10L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.leaderUserId()).isEqualTo(1L);
            assertThat(event.participantUserIds()).containsExactlyInAnyOrder(1L, 2L);
            assertThat(event.totalCost()).isEqualTo(5000L); // 멤버 1명 × 5000
        }

        @Test
        @DisplayName("성공: 만료 대상이 없으면 이벤트 미발행")
        void 만료_대상_없으면_이벤트_미발행() {
            // given
            given(scheduleRepository.findExpiredSchedules(eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(0);

            // when
            scheduleService.updateScheduleStatus();

            // then
            verify(eventPublisher, never()).publishEvent(any());
        }

    }
}
