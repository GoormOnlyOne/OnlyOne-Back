package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
public class UserSettlementRepositoryTest {

    @Autowired UserSettlementRepository userSettlementRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired EntityManager entityManager;

    private Settlement settlement;

    private User alice;
    private User bob;
    private User charlie;

    private UserSettlement usAliceRequested;
    private UserSettlement usBobCompletedRecent;
    private UserSettlement usCharlieFailed;

    @BeforeEach
    void setUp() {
        alice = entityManager.getReference(User.class, 1L);
        bob = entityManager.getReference(User.class, 2L);
        charlie = entityManager.getReference(User.class, 3L);

        settlement = settlementRepository.save(
                Settlement.builder()
                        .scheduleId(901L)
                        .totalStatus(TotalStatus.HOLDING)
                        .receiver(alice)
                        .sum(3000L)
                        .build()
        );

        usAliceRequested = userSettlementRepository.save(
                UserSettlement.builder()
                        .user(alice)
                        .settlement(settlement)
                        .settlementStatus(SettlementStatus.REQUESTED)
                        .build()
        );

        usBobCompletedRecent = userSettlementRepository.save(
                UserSettlement.builder()
                        .user(bob)
                        .settlement(settlement)
                        .settlementStatus(SettlementStatus.COMPLETED)
                        .completedTime(LocalDateTime.now().minusHours(6))
                        .build()
        );

        usCharlieFailed = userSettlementRepository.save(
                UserSettlement.builder()
                        .user(charlie)
                        .settlement(settlement)
                        .settlementStatus(SettlementStatus.FAILED)
                        .build()
        );

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 사용자와_정산으로_조회하면_UserSettlement가_반환된다() {
        Optional<UserSettlement> found = userSettlementRepository.findByUserAndSettlement(alice, settlement);

        assertThat(found).isPresent();
        assertThat(found.get().getSettlementStatus()).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test
    void 정산별_UserSettlement_개수를_반환한다() {
        long count = userSettlementRepository.countBySettlement(settlement);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void 정산과_상태별_UserSettlement_개수를_반환한다() {
        long requested = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.REQUESTED);
        long failed = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.FAILED);
        long completed = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.COMPLETED);

        assertThat(requested).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void 정산별_UserSettlementDto_페이지를_반환한다() {
        Page<UserSettlementDto> page = userSettlementRepository.findAllDtoBySettlement(
                settlement, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(UserSettlementDto::settlementStatus)
                .containsExactlyInAnyOrder(
                        SettlementStatus.REQUESTED,
                        SettlementStatus.COMPLETED,
                        SettlementStatus.FAILED
                );
    }

    @Test
    void 특정_상태가_아닌_UserSettlement가_존재하면_true를_반환한다() {
        boolean aliceHasNonRequested = userSettlementRepository.existsByUserAndSettlementStatusNot(alice, SettlementStatus.REQUESTED);
        boolean bobHasNonCompleted = userSettlementRepository.existsByUserAndSettlementStatusNot(bob, SettlementStatus.COMPLETED);

        assertThat(aliceHasNonRequested).isFalse();
        assertThat(bobHasNonCompleted).isFalse();
    }

    @Test
    void UserSettlement의_상태를_업데이트한다() {
        userSettlementRepository.updateStatusIfRequested(usAliceRequested.getUserSettlementId(), SettlementStatus.COMPLETED);
        entityManager.flush();
        entityManager.clear();

        UserSettlement refreshed = userSettlementRepository.findById(usAliceRequested.getUserSettlementId()).orElseThrow();
        assertThat(refreshed.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void 정산ID와_상태로_UserSettlement_목록을_조회한다() {
        List<UserSettlement> completed = userSettlementRepository
                .findAllBySettlement_SettlementIdAndSettlementStatus(settlement.getSettlementId(), SettlementStatus.COMPLETED);

        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getUser().getUserId()).isEqualTo(bob.getUserId());
    }

    @Test
    void 정산ID와_상태로_참가자_userId_목록을_조회한다() {
        List<Long> userIds = userSettlementRepository
                .findAllUserSettlementIdsBySettlementIdAndStatus(settlement.getSettlementId(), SettlementStatus.REQUESTED);

        assertThat(userIds).hasSize(1);
        assertThat(userIds).contains(alice.getUserId());
    }

    @Test
    void settlementId와_userId로_UserSettlement를_조회한다() {
        Optional<UserSettlement> found = userSettlementRepository
                .findBySettlement_SettlementIdAndUser_UserId(settlement.getSettlementId(), bob.getUserId());

        assertThat(found).isPresent();
        assertThat(found.get().getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void 정산ID로_UserSettlement를_일괄_삭제한다() {
        userSettlementRepository.deleteAllBySettlementId(settlement.getSettlementId());
        entityManager.flush();
        entityManager.clear();

        long count = userSettlementRepository.count();
        assertThat(count).isZero();
    }
}
