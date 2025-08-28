package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
public class SettlementRepositoryTest {
    
    @Autowired
    UserScheduleRepository userScheduleRepository;
    @Autowired
    ScheduleRepository scheduleRepository;
    @Autowired
    ClubRepository clubRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EntityManager entityManager;

    private Club club;
    private Schedule scheduleEnded;
    private Schedule scheduleInProgress;
    private Settlement holdingSettlement;
    private Settlement inProgressSettlement;

    @Autowired
    private UserClubRepository userClubRepository;
    @Autowired
    private SettlementRepository settlementRepository;

    @BeforeEach
    void setUp() {
        Interest interest = entityManager.getReference(Interest.class, 1L);
        User user = entityManager.getReference(User.class, 1L);

        club = clubRepository.save(
                Club.builder()
                        .name("온리원 테스트 모임")
                        .userLimit(10)
                        .description("설명")
                        .city("서울특별시")
                        .district("강남구")
                        .interest(interest)
                        .build()
        );

        scheduleEnded = scheduleRepository.save(
                Schedule.builder()
                        .club(club)
                        .name("스케줄-ENDED")
                        .location("장소")
                        .cost(1000)
                        .userLimit(10)
                        .scheduleStatus(ScheduleStatus.READY)
                        .scheduleTime(LocalDateTime.now().plusDays(1))
                        .build()
        );

        scheduleInProgress = scheduleRepository.save(
                Schedule.builder()
                        .club(club)
                        .name("스케줄-IN_PROGRESS")
                        .location("장소")
                        .cost(1000)
                        .userLimit(10)
                        .scheduleStatus(ScheduleStatus.READY)
                        .scheduleTime(LocalDateTime.now().plusDays(2))
                        .build()
        );

        holdingSettlement = settlementRepository.save(
                Settlement.builder()
                        .schedule(scheduleEnded)
                        .totalStatus(TotalStatus.HOLDING)
                        .receiver(user)
                        .sum(0)
                        .build()
        );

        inProgressSettlement = settlementRepository.save(
                Settlement.builder()
                        .schedule(scheduleInProgress)
                        .totalStatus(TotalStatus.IN_PROGRESS)
                        .receiver(user)
                        .sum(0)
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

        assertThat(holding.get(0).getSchedule().getScheduleId()).isEqualTo(scheduleEnded.getScheduleId());
        assertThat(processing.get(0).getSchedule().getScheduleId()).isEqualTo(scheduleInProgress.getScheduleId());
    }

    @Test
    void HOLDING인_Settlement_1개만_IN_PROGRESS로_갱신한다() {
        // when
        int updated = settlementRepository.markProcessing(holdingSettlement.getSettlementId());

        // then
        assertThat(updated).isEqualTo(1);

        // native UPDATE이므로 실제 DB값 재조회
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
        assertThat(refreshed.getTotalStatus()).isEqualTo(inProgressSettlement.getTotalStatus());
    }
    
}
