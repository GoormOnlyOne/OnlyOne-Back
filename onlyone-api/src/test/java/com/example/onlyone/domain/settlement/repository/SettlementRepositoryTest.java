package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
public class SettlementRepositoryTest {

    @Autowired EntityManager entityManager;
    @Autowired SettlementRepository settlementRepository;

    private static final Long SCHEDULE_ID_1 = 901L;
    private static final Long SCHEDULE_ID_2 = 902L;

    private Settlement holdingSettlement;
    private Settlement inProgressSettlement;

    @BeforeEach
    void setUp() {
        User user = entityManager.getReference(User.class, 1L);

        holdingSettlement = settlementRepository.save(
                Settlement.builder()
                        .scheduleId(SCHEDULE_ID_1)
                        .totalStatus(TotalStatus.HOLDING)
                        .receiver(user)
                        .sum(0L)
                        .build()
        );

        inProgressSettlement = settlementRepository.save(
                Settlement.builder()
                        .scheduleId(SCHEDULE_ID_2)
                        .totalStatus(TotalStatus.IN_PROGRESS)
                        .receiver(user)
                        .sum(0L)
                        .build()
        );

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 특정_상태를_가진_정산_목록을_조회한다() {
        List<Settlement> holding = settlementRepository.findAllByTotalStatus(TotalStatus.HOLDING);
        List<Settlement> processing = settlementRepository.findAllByTotalStatus(TotalStatus.IN_PROGRESS);

        assertThat(holding).hasSize(1);
        assertThat(processing).hasSize(1);

        assertThat(holding.get(0).getScheduleId()).isEqualTo(SCHEDULE_ID_1);
        assertThat(processing.get(0).getScheduleId()).isEqualTo(SCHEDULE_ID_2);
    }

    @Test
    void scheduleId로_정산을_조회한다() {
        var found = settlementRepository.findByScheduleId(SCHEDULE_ID_1);
        assertThat(found).isPresent();
        assertThat(found.get().getTotalStatus()).isEqualTo(TotalStatus.HOLDING);
    }

    @Test
    void 존재하지_않는_scheduleId로_조회하면_빈_결과를_반환한다() {
        var found = settlementRepository.findByScheduleId(999L);
        assertThat(found).isEmpty();
    }

    @Test
    void HOLDING인_Settlement_1개만_IN_PROGRESS로_갱신한다() {
        // when
        int updated = settlementRepository.markProcessing(holdingSettlement.getSettlementId());

        // then
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        Settlement refreshed = settlementRepository.findById(holdingSettlement.getSettlementId()).orElseThrow();
        assertThat(refreshed.getTotalStatus()).isEqualTo(TotalStatus.IN_PROGRESS);
    }

    @Test
    void HOLDING이_아닌_Settlement는_갱신되지_않는다() {
        // when
        int updated = settlementRepository.markProcessing(inProgressSettlement.getSettlementId());
        assertThat(updated).isEqualTo(0);

        entityManager.clear();
        // then
        Settlement refreshed = settlementRepository.findById(inProgressSettlement.getSettlementId()).orElseThrow();
        assertThat(refreshed.getTotalStatus()).isEqualTo(TotalStatus.IN_PROGRESS);
    }
}
