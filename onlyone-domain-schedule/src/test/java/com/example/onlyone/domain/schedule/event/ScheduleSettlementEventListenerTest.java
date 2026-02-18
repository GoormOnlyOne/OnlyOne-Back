package com.example.onlyone.domain.schedule.event;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.example.onlyone.domain.schedule.fixture.ScheduleFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleSettlementEventListener 단위 테스트")
class ScheduleSettlementEventListenerTest {

    @InjectMocks private ScheduleSettlementEventListener listener;
    @Mock private ScheduleRepository scheduleRepository;

    @Test
    @DisplayName("성공: 정산 완료 이벤트 수신 시 스케줄 상태가 CLOSED로 전이된다")
    void onSettlementCompleted_transitionsToClosed() {
        Club club = club();
        Schedule ended = endedSchedule(1L, club);
        SettlementCompletedEvent event = new SettlementCompletedEvent(100L, 1L, 1L, LocalDateTime.now());

        given(scheduleRepository.findById(1L)).willReturn(Optional.of(ended));

        listener.onSettlementCompleted(event);

        assertThat(ended.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
    }

    @Test
    @DisplayName("실패: 스케줄이 존재하지 않으면 SCHEDULE_NOT_FOUND")
    void onSettlementCompleted_scheduleNotFound() {
        SettlementCompletedEvent event = new SettlementCompletedEvent(100L, 999L, 1L, LocalDateTime.now());

        given(scheduleRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onSettlementCompleted(event))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("실패: READY 상태에서 CLOSED 전이 시 IllegalStateException")
    void onSettlementCompleted_invalidTransition() {
        Club club = club();
        Schedule ready = schedule(club);
        SettlementCompletedEvent event = new SettlementCompletedEvent(100L, 1L, 1L, LocalDateTime.now());

        given(scheduleRepository.findById(1L)).willReturn(Optional.of(ready));

        assertThatThrownBy(() -> listener.onSettlementCompleted(event))
                .isInstanceOf(IllegalStateException.class);
    }
}
