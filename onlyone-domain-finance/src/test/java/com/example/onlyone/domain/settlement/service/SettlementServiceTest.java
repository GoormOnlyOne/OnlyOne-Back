package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SettlementServiceTest {

    @InjectMocks
    private SettlementService settlementService;

    @Mock private UserService userService;
    @Mock private ClubRepository clubRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private UserSettlementRepository userSettlementRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OutboxAppender outboxAppender;

    private User leader;
    private Settlement settlement;

    private static final Long CLUB_ID = 1L;
    private static final Long SCHEDULE_ID = 10L;
    private static final Long COST_PER_USER = 100L;

    @BeforeEach
    void setUp() {
        leader = mock(User.class);
        when(leader.getUserId()).thenReturn(1L);

        settlement = mock(Settlement.class);
        when(settlement.getSettlementId()).thenReturn(100L);
    }

    @Test
    void 정상적으로_자동_정산을_요청한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));
        when(settlement.getTotalStatus()).thenReturn(TotalStatus.HOLDING);
        when(settlementRepository.markProcessing(settlement.getSettlementId())).thenReturn(1);
        when(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .thenReturn(List.of(2L, 3L));

        Wallet leaderWallet = mock(Wallet.class);
        when(leaderWallet.getWalletId()).thenReturn(50L);
        when(walletRepository.findByUserWithoutLock(leader)).thenReturn(Optional.of(leaderWallet));

        // when
        settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER);

        // then
        verify(settlement).updateSum(200L); // 2 users * 100
        verify(outboxAppender).append(
                eq("Settlement"),
                eq(100L),
                eq("SettlementProcessEvent"),
                eq("100"),
                any()
        );
    }

    @Test
    void 클럽이_존재하지_않으면_예외가_발생한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(false);

        // when & then
        CustomException ex = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_FOUND);
    }

    @Test
    void 정산이_이미_완료된_경우_예외가_발생한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));
        when(settlement.getTotalStatus()).thenReturn(TotalStatus.COMPLETED);

        // when & then
        CustomException ex = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALREADY_COMPLETED_SETTLEMENT);
    }

    @Test
    void 선점_실패시_ALREADY_SETTLING_SCHEDULE_예외가_발생한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));
        when(settlement.getTotalStatus()).thenReturn(TotalStatus.HOLDING);
        when(settlementRepository.markProcessing(settlement.getSettlementId())).thenReturn(0);

        // when & then
        CustomException ex = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALREADY_SETTLING_SCHEDULE);
    }

    @Test
    void 비용이_0원인_경우_SettlementCompletedEvent를_발행한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));
        when(settlement.getTotalStatus()).thenReturn(TotalStatus.HOLDING);
        when(settlementRepository.markProcessing(settlement.getSettlementId())).thenReturn(1);
        when(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .thenReturn(List.of(2L, 3L));

        // when
        settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, 0L);

        // then
        ArgumentCaptor<SettlementCompletedEvent> captor = ArgumentCaptor.forClass(SettlementCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().scheduleId()).isEqualTo(SCHEDULE_ID);
        verify(outboxAppender, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void 참가자가_없는_경우_SettlementCompletedEvent를_발행한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));
        when(settlement.getTotalStatus()).thenReturn(TotalStatus.HOLDING);
        when(settlementRepository.markProcessing(settlement.getSettlementId())).thenReturn(1);
        when(userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE))
                .thenReturn(List.of());

        // when
        settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER);

        // then
        verify(eventPublisher).publishEvent(any(SettlementCompletedEvent.class));
        verify(outboxAppender, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void 정산이_존재하지_않으면_예외가_발생한다() {
        // given
        when(userService.getCurrentUser()).thenReturn(leader);
        when(clubRepository.existsById(CLUB_ID)).thenReturn(true);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.empty());

        // when & then
        CustomException ex = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(CLUB_ID, SCHEDULE_ID, COST_PER_USER));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SETTLEMENT_NOT_FOUND);
    }

    @Test
    void 스케줄_참여자_정산_목록을_페이징으로_조회한다() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.of(settlement));

        List<UserSettlementDto> dtos = List.of(
                new UserSettlementDto(2L, "Bob", null, SettlementStatus.HOLD_ACTIVE),
                new UserSettlementDto(3L, "Charlie", null, SettlementStatus.HOLD_ACTIVE)
        );
        Page<UserSettlementDto> page = new PageImpl<>(dtos, pageable, 2);
        when(userSettlementRepository.findAllDtoBySettlement(settlement, pageable)).thenReturn(page);

        // when
        SettlementResponseDto result = settlementService.getSettlementList(SCHEDULE_ID, pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.userSettlementList()).hasSize(2);
        assertThat(result.currentPage()).isEqualTo(0);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.totalElement()).isEqualTo(2);
    }

    @Test
    void 조회시_정산이_없으면_예외가_발생한다() {
        // given
        when(settlementRepository.findByScheduleId(SCHEDULE_ID)).thenReturn(Optional.empty());

        // when & then
        CustomException ex = assertThrows(CustomException.class, () ->
                settlementService.getSettlementList(SCHEDULE_ID, PageRequest.of(0, 10)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SETTLEMENT_NOT_FOUND);
    }
}
