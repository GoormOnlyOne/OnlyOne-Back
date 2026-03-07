package com.example.onlyone.domain.settlement.event;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.example.onlyone.domain.settlement.fixture.FinanceFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementScheduleEventListener 단위 테스트")
class SettlementScheduleEventListenerTest {

    @InjectMocks private SettlementScheduleEventListener listener;

    @Mock private SettlementRepository settlementRepository;
    @Mock private UserSettlementRepository userSettlementRepository;
    @Mock private UserRepository userRepository;

    @Nested
    @DisplayName("ScheduleCreatedEvent 처리")
    class HandleCreated {

        @Test
        @DisplayName("성공: Settlement 초기화 (receiver=리더)")
        void Settlement_초기화() {
            // given
            User leader = leader();
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, CLUB_ID, 1L, "정기 모임", LocalDateTime.now());
            given(userRepository.findById(1L)).willReturn(Optional.of(leader));
            given(settlementRepository.save(any(Settlement.class))).willAnswer(inv -> inv.getArgument(0));

            // when
            listener.handleScheduleCreatedEvent(event);

            // then
            ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
            then(settlementRepository).should().save(captor.capture());
            Settlement saved = captor.getValue();
            assertThat(saved.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(saved.getSum()).isZero();
            assertThat(saved.getTotalStatus()).isEqualTo(TotalStatus.HOLDING);
            assertThat(saved.getReceiver()).isEqualTo(leader);
        }
    }

    @Nested
    @DisplayName("ScheduleJoinedEvent 처리")
    class HandleJoined {

        @Test
        @DisplayName("성공: UserSettlement 생성 (HOLD_ACTIVE)")
        void UserSettlement_생성() {
            // given
            User leader = leader();
            User member = member();
            Settlement settlement = settlement(leader);
            ScheduleJoinedEvent event = new ScheduleJoinedEvent(SCHEDULE_ID, CLUB_ID, 2L, 5000L);
            given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));

            // when
            listener.handleScheduleJoinedEvent(event);

            // then
            ArgumentCaptor<UserSettlement> captor = ArgumentCaptor.forClass(UserSettlement.class);
            then(userSettlementRepository).should().save(captor.capture());
            UserSettlement saved = captor.getValue();
            assertThat(saved.getSettlementStatus()).isEqualTo(SettlementStatus.HOLD_ACTIVE);
            assertThat(saved.getSettlement()).isEqualTo(settlement);
            assertThat(saved.getUser()).isEqualTo(member);
        }
    }

    @Nested
    @DisplayName("ScheduleLeftEvent 처리")
    class HandleLeft {

        @Test
        @DisplayName("성공: UserSettlement 삭제")
        void UserSettlement_삭제() {
            // given
            User leader = leader();
            User member = member();
            Settlement settlement = settlement(leader);
            UserSettlement us = userSettlement(member, settlement);
            ScheduleLeftEvent event = new ScheduleLeftEvent(SCHEDULE_ID, CLUB_ID, 2L);

            given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));
            given(userSettlementRepository.findByUserAndSettlement(member, settlement))
                    .willReturn(Optional.of(us));

            // when
            listener.handleScheduleLeftEvent(event);

            // then
            then(userSettlementRepository).should().delete(us);
        }
    }

    @Nested
    @DisplayName("ScheduleDeletedEvent 처리")
    class HandleDeleted {

        @Test
        @DisplayName("성공: Settlement 삭제 (cascade)")
        void Settlement_삭제() {
            // given
            ScheduleDeletedEvent event = new ScheduleDeletedEvent(SCHEDULE_ID, CLUB_ID);

            // when
            listener.handleScheduleDeletedEvent(event);

            // then
            then(settlementRepository).should().deleteByScheduleId(SCHEDULE_ID);
        }
    }
}
