package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.settlement.fixture.FinanceFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementCommandService 단위 테스트")
class SettlementCommandServiceTest {

    @InjectMocks private SettlementCommandService settlementCommandService;

    @Mock private UserService userService;
    @Mock private ClubRepository clubRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private UserSettlementRepository userSettlementRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OutboxAppender outboxAppender;

    @Test
    @DisplayName("성공: 정상적으로 자동 정산을 요청한다")
    void success() {
        // given
        User leader = leader();
        Settlement settlement = settlement(leader);
        Wallet wallet = leaderWallet(leader);

        given(userService.getCurrentUser()).willReturn(leader);
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
        given(settlementRepository.markProcessing(settlement.getSettlementId())).willReturn(1);
        given(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .willReturn(List.of(2L, 3L));
        given(walletRepository.findByUserWithoutLock(leader)).willReturn(Optional.of(wallet));

        // when
        settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER);

        // then
        assertThat(settlement.getSum()).isEqualTo(200L);
        then(outboxAppender).should().append(
                eq("Settlement"),
                eq(100L),
                eq("SettlementProcessEvent"),
                eq("100"),
                any()
        );
    }

    @Test
    @DisplayName("실패: 클럽이 존재하지 않으면 CLUB_NOT_FOUND")
    void clubNotFound() {
        given(userService.getCurrentUser()).willReturn(leader());
        given(clubRepository.existsById(CLUB_ID)).willReturn(false);

        assertThatThrownBy(() -> settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
    }

    @Test
    @DisplayName("실패: 정산이 이미 완료된 경우 ALREADY_COMPLETED_SETTLEMENT")
    void alreadyCompleted() {
        User leader = leader();
        Settlement completed = completedSettlement(leader);

        given(userService.getCurrentUser()).willReturn(leader);
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(completed));

        assertThatThrownBy(() -> settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_COMPLETED_SETTLEMENT);
    }

    @Test
    @DisplayName("실패: 선점 실패 시 ALREADY_SETTLING_SCHEDULE")
    void markProcessingFailed() {
        User leader = leader();
        Settlement settlement = settlement(leader);

        given(userService.getCurrentUser()).willReturn(leader);
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
        given(settlementRepository.markProcessing(settlement.getSettlementId())).willReturn(0);

        assertThatThrownBy(() -> settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_SETTLING_SCHEDULE);
    }

    @Test
    @DisplayName("성공: 비용이 0원인 경우 SettlementCompletedEvent를 발행한다")
    void zeroCost_publishesCompletedEvent() {
        User leader = leader();
        Settlement settlement = settlement(leader);

        given(userService.getCurrentUser()).willReturn(leader);
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
        given(settlementRepository.markProcessing(settlement.getSettlementId())).willReturn(1);
        given(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .willReturn(List.of(2L, 3L));

        // when
        settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, 0L);

        // then
        ArgumentCaptor<SettlementCompletedEvent> captor = ArgumentCaptor.forClass(SettlementCompletedEvent.class);
        then(eventPublisher).should().publishEvent(captor.capture());
        assertThat(captor.getValue().scheduleId()).isEqualTo(SCHEDULE_ID);
        then(outboxAppender).should(never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("성공: 참가자가 없는 경우 SettlementCompletedEvent를 발행한다")
    void noParticipants_publishesCompletedEvent() {
        User leader = leader();
        Settlement settlement = settlement(leader);

        given(userService.getCurrentUser()).willReturn(leader);
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));
        given(settlementRepository.markProcessing(settlement.getSettlementId())).willReturn(1);
        given(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .willReturn(List.of());

        // when
        settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER);

        // then
        then(eventPublisher).should().publishEvent(any(SettlementCompletedEvent.class));
        then(outboxAppender).should(never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("실패: 정산이 존재하지 않으면 SETTLEMENT_NOT_FOUND")
    void settlementNotFound() {
        given(userService.getCurrentUser()).willReturn(leader());
        given(clubRepository.existsById(CLUB_ID)).willReturn(true);
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementCommandService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SETTLEMENT_NOT_FOUND);
    }
}
