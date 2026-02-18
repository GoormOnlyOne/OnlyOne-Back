package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalletHoldServiceImpl 단위 테스트")
class WalletHoldServiceImplTest {

    @InjectMocks private WalletHoldServiceImpl walletHoldService;
    @Mock private WalletRepository walletRepository;

    @Nested
    @DisplayName("holdOrThrow")
    class HoldOrThrow {

        @Test
        @DisplayName("성공: 잔액이 충분하면 hold 성공")
        void success() {
            given(walletRepository.holdBalanceIfEnough(1L, 5000L)).willReturn(1);

            assertThatCode(() -> walletHoldService.holdOrThrow(1L, 5000L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("실패: 잔액 부족 시 WALLET_BALANCE_NOT_ENOUGH")
        void insufficientBalance() {
            given(walletRepository.holdBalanceIfEnough(1L, 5000L)).willReturn(0);

            assertThatThrownBy(() -> walletHoldService.holdOrThrow(1L, 5000L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }
    }

    @Nested
    @DisplayName("releaseOrThrow")
    class ReleaseOrThrow {

        @Test
        @DisplayName("성공: hold 해제 성공")
        void success() {
            given(walletRepository.releaseHoldBalance(1L, 5000L)).willReturn(1);

            assertThatCode(() -> walletHoldService.releaseOrThrow(1L, 5000L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("실패: hold 상태 충돌 시 WALLET_HOLD_STATE_CONFLICT")
        void holdStateConflict() {
            given(walletRepository.releaseHoldBalance(1L, 5000L)).willReturn(0);

            assertThatThrownBy(() -> walletHoldService.releaseOrThrow(1L, 5000L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.WALLET_HOLD_STATE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("batchRelease")
    class BatchRelease {

        @Test
        @DisplayName("성공: 여러 사용자의 hold를 일괄 해제한다")
        void success() {
            List<Long> userIds = List.of(1L, 2L, 3L);

            walletHoldService.batchRelease(userIds, 5000L);

            then(walletRepository).should().batchReleaseHoldBalance(userIds, 5000L);
        }

        @Test
        @DisplayName("빈 리스트인 경우 repository를 호출하지 않는다")
        void emptyList_skipsRepository() {
            walletHoldService.batchRelease(List.of(), 5000L);

            then(walletRepository).shouldHaveNoInteractions();
        }
    }
}
