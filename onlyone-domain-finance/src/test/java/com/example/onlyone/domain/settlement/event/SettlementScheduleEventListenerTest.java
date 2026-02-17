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
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementScheduleEventListener 단위 테스트")
class SettlementScheduleEventListenerTest {

    @InjectMocks
    private SettlementScheduleEventListener listener;

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private UserSettlementRepository userSettlementRepository;
    @Mock
    private UserRepository userRepository;

    private User leader;
    private User member;
    private Settlement settlement;

    @BeforeEach
    void setUp() {
        leader = User.builder().userId(1L).nickname("리더").build();
        member = User.builder().userId(2L).nickname("멤버").build();
        settlement = Settlement.builder()
                .settlementId(100L)
                .scheduleId(10L)
                .sum(0L)
                .totalStatus(TotalStatus.HOLDING)
                .receiver(leader)
                .build();
    }

    @Nested
    @DisplayName("ScheduleCreatedEvent 처리")
    class HandleCreated {

        @Test
        @DisplayName("성공: Settlement 초기화 (receiver=리더)")
        void Settlement_초기화() {
            // given
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(10L, 1L, 1L, "정기 모임", LocalDateTime.now());
            given(userRepository.findById(1L)).willReturn(Optional.of(leader));
            given(settlementRepository.save(any(Settlement.class))).willAnswer(inv -> inv.getArgument(0));

            // when
            listener.handleScheduleCreatedEvent(event);

            // then
            ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
            verify(settlementRepository).save(captor.capture());
            Settlement saved = captor.getValue();
            assertThat(saved.getScheduleId()).isEqualTo(10L);
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
            ScheduleJoinedEvent event = new ScheduleJoinedEvent(10L, 1L, 2L, 5000L);
            given(settlementRepository.findByScheduleId(10L)).willReturn(Optional.of(settlement));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));

            // when
            listener.handleScheduleJoinedEvent(event);

            // then
            ArgumentCaptor<UserSettlement> captor = ArgumentCaptor.forClass(UserSettlement.class);
            verify(userSettlementRepository).save(captor.capture());
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
            ScheduleLeftEvent event = new ScheduleLeftEvent(10L, 1L, 2L);
            UserSettlement us = UserSettlement.builder()
                    .userSettlementId(1L)
                    .user(member)
                    .settlement(settlement)
                    .settlementStatus(SettlementStatus.HOLD_ACTIVE)
                    .build();

            given(settlementRepository.findByScheduleId(10L)).willReturn(Optional.of(settlement));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));
            given(userSettlementRepository.findByUserAndSettlement(member, settlement))
                    .willReturn(Optional.of(us));

            // when
            listener.handleScheduleLeftEvent(event);

            // then
            verify(userSettlementRepository).delete(us);
        }
    }

    @Nested
    @DisplayName("ScheduleDeletedEvent 처리")
    class HandleDeleted {

        @Test
        @DisplayName("성공: Settlement 삭제 (cascade)")
        void Settlement_삭제() {
            // given
            ScheduleDeletedEvent event = new ScheduleDeletedEvent(10L, 1L);

            // when
            listener.handleScheduleDeletedEvent(event);

            // then
            verify(settlementRepository).deleteByScheduleId(10L);
        }
    }
}
