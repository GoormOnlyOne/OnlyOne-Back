package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.wallet.entity.Wallet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
class WalletRepositoryTest {

    @Autowired WalletRepository walletRepository;
    @Autowired EntityManager entityManager;

    private User user1;
    private User user2;

    @BeforeEach
    void setUp() {
        user1 = entityManager.getReference(User.class, 1L);
        user2 = entityManager.getReference(User.class, 2L);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 유저로_지갑을_락과_함께_조회한다() {
        // when
        Optional<Wallet> locked = walletRepository.findByUser(user1);

        // then
        assertThat(locked).isPresent();
        assertThat(locked.get().getUser().getUserId()).isEqualTo(user1.getUserId());
    }

    @Test
    void 유저로_지갑을_락_없이_조회한다() {
        // given

        // when
        Optional<Wallet> wallet = walletRepository.findByUserWithoutLock(user1);

        // then
        assertThat(wallet).isPresent();
        assertThat(wallet.get().getUser().getUserId()).isEqualTo(user1.getUserId());
    }

    @Test
    void 잔액이_충분하면_정산_예약금_홀드가_성공한다() {
        // given
        Wallet w = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        entityManager.clear();
        long amount = Math.max(1, w.getPostedBalance() - w.getPendingOut()); // 가능한 최소 양으로 홀드
        // when
        int updated = walletRepository.holdBalanceIfEnough(user1.getUserId(), amount);

        // then
        assertThat(updated).isEqualTo(1);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(w.getPendingOut() + amount);
        assertThat(refreshed.getPostedBalance()).isEqualTo(w.getPostedBalance());
    }

    @Test
    void 잔액이_부족하면_정산_예약금_홀드가_거절된다() {
        // given
        Wallet w = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long impossible = (w.getPostedBalance() - w.getPendingOut()) + 1; // 반드시 실패하는 양
        // when
        int updated = walletRepository.holdBalanceIfEnough(user1.getUserId(), impossible);

        // then
        assertThat(updated).isEqualTo(0);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(w.getPendingOut());
        assertThat(refreshed.getPostedBalance()).isEqualTo(w.getPostedBalance());
    }

    @Test
    void 정산_예약금_홀드_해제가_정상적으로_성공한다() {
        // given
        Wallet before = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long holdAmt = Math.max(2, (before.getPostedBalance() - before.getPendingOut()));
        walletRepository.holdBalanceIfEnough(user1.getUserId(), holdAmt);
        entityManager.clear();

        Wallet afterHold = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long release = holdAmt / 2;

        // when
        int released = walletRepository.releaseHoldBalance(user1.getUserId(), release);

        // then
        assertThat(released).isEqualTo(1);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(afterHold.getPendingOut() - release);
        assertThat(refreshed.getPostedBalance()).isEqualTo(afterHold.getPostedBalance());
    }

    @Test
    void 보유한_홀드보다_많이_해제하면_실패한다() {
        // given
        Wallet w = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long releaseTooMuch = w.getPendingOut() + 1;

        // when
        int released = walletRepository.releaseHoldBalance(user1.getUserId(), releaseTooMuch);

        // then
        assertThat(released).isEqualTo(0);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(w.getPendingOut());
        assertThat(refreshed.getPostedBalance()).isEqualTo(w.getPostedBalance());
    }

    @Test
    void 정산_예약금_홀드를_캡처하면_posted와_pending이_같이_줄어든다() {
        // given
        Wallet before = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long maxCapturable = Math.max(1, Math.min(
                before.getPostedBalance() - before.getPendingOut(),
                before.getPostedBalance()
        ));
        walletRepository.holdBalanceIfEnough(user1.getUserId(), maxCapturable);
        entityManager.clear();

        Wallet afterHold = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long capture = Math.min(afterHold.getPendingOut(), afterHold.getPostedBalance());

        // when
        int captured = walletRepository.captureHold(user1.getUserId(), capture);

        // then
        assertThat(captured).isEqualTo(1);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(afterHold.getPendingOut() - capture);
        assertThat(refreshed.getPostedBalance()).isEqualTo(afterHold.getPostedBalance() - capture);
    }

    @Test
    void 보유한_홀드보다_많이_캡처하면_실패한다() {
        // given
        Wallet w = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        long tooMuch = w.getPendingOut() + 1;

        // when
        int captured = walletRepository.captureHold(user1.getUserId(), tooMuch);

        // then
        assertThat(captured).isEqualTo(0);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user1).orElseThrow();
        assertThat(refreshed.getPendingOut()).isEqualTo(w.getPendingOut());
        assertThat(refreshed.getPostedBalance()).isEqualTo(w.getPostedBalance());
    }

    @Test
    void 정산_리더를_위한_크레딧이_성공한다() {
        // given
        Wallet w = walletRepository.findByUserWithoutLock(user2).orElseThrow();
        long amount = 1234L;

        // when
        int credited = walletRepository.creditByUserId(user2.getUserId(), amount);

        // then
        assertThat(credited).isEqualTo(1);
        entityManager.clear();
        Wallet refreshed = walletRepository.findByUserWithoutLock(user2).orElseThrow();
        assertThat(refreshed.getPostedBalance()).isEqualTo(w.getPostedBalance() + amount);
        assertThat(refreshed.getPendingOut()).isEqualTo(w.getPendingOut());
    }
}
