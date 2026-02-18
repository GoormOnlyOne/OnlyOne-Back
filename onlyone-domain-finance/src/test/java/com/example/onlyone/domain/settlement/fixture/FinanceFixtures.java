package com.example.onlyone.domain.settlement.fixture;

import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.wallet.entity.Wallet;

import java.time.LocalDate;

public final class FinanceFixtures {

    private FinanceFixtures() {}

    public static final Long CLUB_ID = 1L;
    public static final Long SCHEDULE_ID = 10L;
    public static final Long COST_PER_USER = 100L;

    // ==================== User ====================

    public static User leader() {
        return User.builder()
                .userId(1L)
                .kakaoId(1000L)
                .nickname("리더")
                .birth(LocalDate.of(1995, 1, 1))
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .city("서울특별시")
                .district("강남구")
                .build();
    }

    public static User member() {
        return User.builder()
                .userId(2L)
                .kakaoId(2000L)
                .nickname("멤버")
                .birth(LocalDate.of(1996, 1, 1))
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .city("서울특별시")
                .district("강남구")
                .build();
    }

    // ==================== Wallet ====================

    public static Wallet leaderWallet(User leader) {
        return Wallet.builder()
                .walletId(50L)
                .user(leader)
                .postedBalance(100_000L)
                .pendingOut(0L)
                .build();
    }

    public static Wallet memberWallet(User member) {
        return Wallet.builder()
                .walletId(60L)
                .user(member)
                .postedBalance(50_000L)
                .pendingOut(0L)
                .build();
    }

    // ==================== Settlement ====================

    public static Settlement settlement(User receiver) {
        return Settlement.builder()
                .settlementId(100L)
                .scheduleId(SCHEDULE_ID)
                .sum(0L)
                .totalStatus(TotalStatus.HOLDING)
                .receiver(receiver)
                .build();
    }

    public static Settlement completedSettlement(User receiver) {
        return Settlement.builder()
                .settlementId(100L)
                .scheduleId(SCHEDULE_ID)
                .sum(200L)
                .totalStatus(TotalStatus.COMPLETED)
                .receiver(receiver)
                .build();
    }

    // ==================== UserSettlement ====================

    public static UserSettlement userSettlement(User user, Settlement settlement) {
        return UserSettlement.builder()
                .userSettlementId(1L)
                .user(user)
                .settlement(settlement)
                .settlementStatus(SettlementStatus.HOLD_ACTIVE)
                .build();
    }

    public static UserSettlement completedUserSettlement(User user, Settlement settlement) {
        return UserSettlement.builder()
                .userSettlementId(1L)
                .user(user)
                .settlement(settlement)
                .settlementStatus(SettlementStatus.COMPLETED)
                .build();
    }
}
