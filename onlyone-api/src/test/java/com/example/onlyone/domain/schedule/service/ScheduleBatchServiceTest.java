package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.common.event.ScheduleCompletedEvent;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.schedule.fixture.ScheduleFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleBatchService 단위 테스트")
class ScheduleBatchServiceTest {

    @InjectMocks private ScheduleBatchService scheduleBatchService;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private User leader;
    private User member;
    private Club club;

    @BeforeEach
    void setUp() {
        leader = leader();
        member = member();
        club = club();
    }

    @Nested
    @DisplayName("자정 배치 스케줄 상태 업데이트")
    class UpdateScheduleStatus {

        @Test
        @DisplayName("성공: 만료된 스케줄 READY->ENDED 전환 + ScheduleCompletedEvent 발행")
        void expiredScheduleTransition() {
            Schedule expired = expiredSchedule(10L, club);

            given(scheduleRepository.findExpiredSchedules(eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(List.of(expired));
            given(scheduleRepository.updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(1);
            given(userScheduleRepository.findLeaderByScheduleAndScheduleRole(expired, ScheduleRole.LEADER))
                    .willReturn(Optional.of(leader));
            given(userScheduleRepository.findUsersBySchedule(expired))
                    .willReturn(List.of(leader, member));

            scheduleBatchService.updateScheduleStatus();

            then(scheduleRepository).should().updateExpiredSchedules(
                    eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class));

            ArgumentCaptor<ScheduleCompletedEvent> captor =
                    ArgumentCaptor.forClass(ScheduleCompletedEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());

            ScheduleCompletedEvent event = captor.getValue();
            assertThat(event.scheduleId()).isEqualTo(10L);
            assertThat(event.clubId()).isEqualTo(1L);
            assertThat(event.leaderUserId()).isEqualTo(1L);
            assertThat(event.participantUserIds()).containsExactlyInAnyOrder(1L, 2L);
            assertThat(event.totalCost()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("성공: 만료 대상이 없으면 이벤트 미발행")
        void noExpiredSchedules() {
            given(scheduleRepository.findExpiredSchedules(eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(0);

            scheduleBatchService.updateScheduleStatus();

            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("성공: 리더가 없는 스케줄은 이벤트 미발행 (경고 로그)")
        void noLeaderSkipsEvent() {
            Schedule expired = expiredSchedule(20L, club);

            given(scheduleRepository.findExpiredSchedules(eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(List.of(expired));
            given(scheduleRepository.updateExpiredSchedules(eq(ScheduleStatus.ENDED), eq(ScheduleStatus.READY), any(LocalDateTime.class)))
                    .willReturn(1);
            given(userScheduleRepository.findLeaderByScheduleAndScheduleRole(expired, ScheduleRole.LEADER))
                    .willReturn(Optional.empty());

            scheduleBatchService.updateScheduleStatus();

            then(eventPublisher).should(never()).publishEvent(any());
        }
    }
}
