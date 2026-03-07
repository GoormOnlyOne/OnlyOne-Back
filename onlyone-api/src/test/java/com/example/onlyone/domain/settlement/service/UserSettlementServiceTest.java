package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.event.FailedSettlementContext;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.service.WalletGateService;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.domain.user.exception.UserErrorCode;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.example.onlyone.domain.settlement.fixture.FinanceFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSettlementService 단위 테스트")
class UserSettlementServiceTest {

    @InjectMocks private UserSettlementService userSettlementService;

    @Mock private UserSettlementRepository userSettlementRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private UserRepository userRepository;
    @Mock private WalletGateService walletGateService;
    @Mock private OutboxAppender outboxAppender;
    @Mock private FailedEventAppender failedEventAppender;

    /** WalletGateService mock — body를 즉시 실행 */
    private void stubGateToRunBody() {
        willAnswer(inv -> { inv.<Runnable>getArgument(3).run(); return null; })
                .given(walletGateService).withWalletGate(anyLong(), anyString(), anyInt(), any(Runnable.class));
    }

    @Nested
    @DisplayName("processParticipantSettlement")
    class ProcessParticipantSettlement {

        @Test
        @DisplayName("성공: captureHold + markCompleted + Outbox SUCCESS 기록")
        void success() {
            // given
            User leader = leader();
            User member = member();
            stubGateToRunBody();

            UserSettlement us = userSettlement(member, settlement(leader));
            Wallet wallet = memberWallet(member);

            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.of(us));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));
            given(walletRepository.findByUserWithoutLock(member)).willReturn(Optional.of(wallet));
            given(walletRepository.captureHold(2L, 100L)).willReturn(1);

            // when
            userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L);

            // then
            assertThat(us.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
            then(userSettlementRepository).should().save(us);
            then(outboxAppender).should().append(
                    eq("UserSettlement"), eq(1L), eq("ParticipantSettlementResult"),
                    eq("60"), any()
            );
        }

        @Test
        @DisplayName("성공: 이미 COMPLETED인 경우 멱등 스킵")
        void idempotentSkip() {
            // given
            User leader = leader();
            User member = member();
            stubGateToRunBody();

            UserSettlement completed = completedUserSettlement(member, settlement(leader));
            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.of(completed));

            // when
            userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L);

            // then
            then(walletRepository).should(never()).captureHold(anyLong(), anyLong());
            then(outboxAppender).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("실패: captureHold 실패 시 FailedEventAppender 호출 후 예외")
        void captureHoldFailed_triggersFailedEvent() {
            // given
            User leader = leader();
            User member = member();
            stubGateToRunBody();

            UserSettlement us = userSettlement(member, settlement(leader));
            Wallet wallet = memberWallet(member);

            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.of(us));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));
            given(walletRepository.findByUserWithoutLock(member)).willReturn(Optional.of(wallet));
            given(walletRepository.captureHold(2L, 100L)).willReturn(0);

            // when & then
            assertThatThrownBy(() -> userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(FinanceErrorCode.WALLET_HOLD_CAPTURE_FAILED);

            then(failedEventAppender).should().appendFailedUserSettlementEvent(any(FailedSettlementContext.class));
        }

        @Test
        @DisplayName("실패: UserSettlement이 존재하지 않으면 USER_SETTLEMENT_NOT_FOUND")
        void userSettlementNotFound() {
            stubGateToRunBody();

            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(FinanceErrorCode.USER_SETTLEMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 참가자 User가 존재하지 않으면 USER_NOT_FOUND")
        void userNotFound() {
            // given
            User leader = leader();
            User member = member();
            stubGateToRunBody();

            UserSettlement us = userSettlement(member, settlement(leader));
            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.of(us));
            given(userRepository.findById(2L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 참가자 Wallet이 존재하지 않으면 WALLET_NOT_FOUND")
        void walletNotFound() {
            // given
            User leader = leader();
            User member = member();
            stubGateToRunBody();

            UserSettlement us = userSettlement(member, settlement(leader));
            given(userSettlementRepository.findBySettlement_SettlementIdAndUser_UserId(100L, 2L))
                    .willReturn(Optional.of(us));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));
            given(walletRepository.findByUserWithoutLock(member)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userSettlementService.processParticipantSettlement(100L, 1L, 50L, 2L, 100L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(FinanceErrorCode.WALLET_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("creditToLeader")
    class CreditToLeader {

        @Test
        @DisplayName("성공: 리더에게 총액 가산")
        void success() {
            // given
            willAnswer(inv -> { inv.<Runnable>getArgument(3).run(); return null; })
                    .given(walletGateService).withWalletGate(anyLong(), anyString(), anyInt(), any(Runnable.class));
            given(walletRepository.creditByUserId(1L, 200L)).willReturn(1);

            // when
            userSettlementService.creditToLeader(1L, 200L);

            // then
            then(walletRepository).should().creditByUserId(1L, 200L);
        }

        @Test
        @DisplayName("실패: credit 실패 시 WALLET_CREDIT_APPLY_FAILED")
        void creditFailed() {
            // given
            willAnswer(inv -> { inv.<Runnable>getArgument(3).run(); return null; })
                    .given(walletGateService).withWalletGate(anyLong(), anyString(), anyInt(), any(Runnable.class));
            given(walletRepository.creditByUserId(1L, 200L)).willReturn(0);

            // when & then
            assertThatThrownBy(() -> userSettlementService.creditToLeader(1L, 200L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(FinanceErrorCode.WALLET_CREDIT_APPLY_FAILED);
        }
    }
}
