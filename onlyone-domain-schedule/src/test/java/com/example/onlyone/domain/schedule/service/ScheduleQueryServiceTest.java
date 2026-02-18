package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.dto.response.ScheduleDetailResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleUserResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.schedule.fixture.ScheduleFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleQueryService 단위 테스트")
class ScheduleQueryServiceTest {

    @InjectMocks private ScheduleQueryService scheduleQueryService;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserService userService;

    private User leader;
    private User member;
    private Club club;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        leader = leader();
        member = member();
        club = club();
        schedule = schedule(club);
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
            Schedule schedule2 = schedule(2L, club);

            List<Object[]> queryResult = List.of(
                    new Object[]{schedule2, 0L, null},
                    new Object[]{schedule, 1L, ScheduleRole.LEADER}
            );

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(leader);
            given(scheduleRepository.findScheduleListWithUserInfo(club, leader)).willReturn(queryResult);

            List<ScheduleResponseDto> result = scheduleQueryService.getScheduleList(1L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("정기 모임");
            assertThat(result.get(1).name()).isEqualTo("정기 모임");
            assertThat(result.get(1).isJoined()).isTrue();
            assertThat(result.get(1).isLeader()).isTrue();
            assertThat(result.get(0).isJoined()).isFalse();
        }

        @Test
        @DisplayName("실패: 모임이 존재하지 않으면 CLUB_NOT_FOUND")
        void 모임이_존재하지_않으면_CLUB_NOT_FOUND() {
            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleQueryService.getScheduleList(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }
    }

    // =====================================================================
    // 정기모임 참여자 목록 조회
    // =====================================================================
    @Nested
    @DisplayName("정기모임 참여자 목록 조회")
    class GetScheduleUserList {

        @Test
        @DisplayName("성공: 참여자 목록 반환")
        void 참여자_목록_반환() {
            given(userScheduleRepository.findUsersByScheduleIdAndClubId(1L, 1L))
                    .willReturn(List.of(leader, member));

            List<ScheduleUserResponseDto> result = scheduleQueryService.getScheduleUserList(1L, 1L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).nickname()).isEqualTo("리더");
            assertThat(result.get(1).nickname()).isEqualTo("멤버");
        }

        @Test
        @DisplayName("성공: 참여자가 없으면 빈 목록 반환")
        void 참여자가_없으면_빈_목록_반환() {
            given(userScheduleRepository.findUsersByScheduleIdAndClubId(1L, 1L))
                    .willReturn(List.of());

            List<ScheduleUserResponseDto> result = scheduleQueryService.getScheduleUserList(1L, 1L);

            assertThat(result).isEmpty();
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
            given(scheduleRepository.findByIdAndClubId(1L, 1L)).willReturn(Optional.of(schedule));

            ScheduleDetailResponseDto result = scheduleQueryService.getScheduleDetails(1L, 1L);

            assertThat(result).isNotNull();
            assertThat(result.scheduleId()).isEqualTo(1L);
            assertThat(result.name()).isEqualTo("정기 모임");
            assertThat(result.location()).isEqualTo("구름스퀘어 강남");
            assertThat(result.cost()).isEqualTo(10000L);
            assertThat(result.userLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("실패: 스케줄이 존재하지 않으면 SCHEDULE_NOT_FOUND")
        void 스케줄이_존재하지_않으면_SCHEDULE_NOT_FOUND() {
            given(scheduleRepository.findByIdAndClubId(999L, 1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleQueryService.getScheduleDetails(1L, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
        }
    }
}
