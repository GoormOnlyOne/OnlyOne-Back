package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.dto.response.MySettlementDto;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
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
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private Club club;
    private Schedule schedule;
    private Settlement settlement;

    private User alice;
    private User bob;
    private User charlie;

    private UserSettlement usAliceRequested;
    private UserSettlement usBobCompletedRecent;
    private UserSettlement usCharlieFailed;

    @BeforeEach
    void setUp() {
        Interest interest = entityManager.getReference(Interest.class, 1L);
        alice = entityManager.getReference(User.class, 1L);
        bob = entityManager.getReference(User.class, 2L);
        charlie = entityManager.getReference(User.class, 3L);

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

        schedule = scheduleRepository.save(
                Schedule.builder()
                        .club(club)
                        .name("테스트 스케줄")
                        .location("장소")
                        .cost(1000)
                        .userLimit(10)
                        .scheduleStatus(ScheduleStatus.ENDED)
                        .scheduleTime(LocalDateTime.now().minusHours(1))
                        .build()
        );

        settlement = settlementRepository.save(
                Settlement.builder()
                        .schedule(schedule)
                        .totalStatus(TotalStatus.HOLDING)
                        .receiver(alice)
                        .sum(3000)
                        .build()
        );

        // UserSettlement 샘플 3건: REQUESTED / COMPLETED(최근) / FAILED
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
        // when
        Optional<UserSettlement> found = userSettlementRepository.findByUserAndSettlement(alice, settlement);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getSettlementStatus()).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test
    void 정산별_UserSettlement_개수를_반환한다() {
        // when
        long count = userSettlementRepository.countBySettlement(settlement);

        // then
        assertThat(count).isEqualTo(3);
    }

    @Test
    void 정산과_상태별_UserSettlement_개수를_반환한다() {
        // when
        long requested = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.REQUESTED);
        long failed = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.FAILED);
        long completed = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.COMPLETED);

        // then
        assertThat(requested).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void 정산별_UserSettlementDto_페이지를_반환한다() {
        // when
        Page<UserSettlementDto> page = userSettlementRepository.findAllDtoBySettlement(
                settlement, PageRequest.of(0, 10));

        // then
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(UserSettlementDto::getSettlementStatus)
                .containsExactlyInAnyOrder(
                        SettlementStatus.REQUESTED,
                        SettlementStatus.COMPLETED,
                        SettlementStatus.FAILED
                );
    }

    @Test
    void 사용자의_최근완료_요청_실패_정산목록을_반환한다O() {
        // given: cutoff = 24시간 전 (Bob의 completedTime -6h 는 포함)
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);

        // when
        Page<MySettlementDto> alicePage = userSettlementRepository.findMyRecentOrRequested(
                alice, cutoff, PageRequest.of(0, 10));
        Page<MySettlementDto> bobPage = userSettlementRepository.findMyRecentOrRequested(
                bob, cutoff, PageRequest.of(0, 10));
        Page<MySettlementDto> charliePage = userSettlementRepository.findMyRecentOrRequested(
                charlie, cutoff, PageRequest.of(0, 10));

        // then
        // Alice: REQUESTED 1건
        assertThat(alicePage.getTotalElements()).isEqualTo(1);
        assertThat(alicePage.getContent().get(0).getSettlementStatus()).isEqualTo(SettlementStatus.REQUESTED);

        // Bob: COMPLETED & completedTime >= cutoff → 1건
        assertThat(bobPage.getTotalElements()).isEqualTo(1);
        assertThat(bobPage.getContent().get(0).getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);

        // Charlie: FAILED 포함 → 1건
        assertThat(charliePage.getTotalElements()).isEqualTo(1);
        assertThat(charliePage.getContent().get(0).getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
    }

    @Test
    void 사용자와_스케줄로_UserSettlement를_조회한다() {
        // when
        Optional<UserSettlement> found = userSettlementRepository.findByUserAndSchedule(alice, schedule);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getUser().getUserId()).isEqualTo(alice.getUserId());
        assertThat(found.get().getSettlement().getSchedule().getScheduleId()).isEqualTo(schedule.getScheduleId());
    }

    @Test
    void 특정_상태가_아닌_UserSettlement가_존재하면_true를_반환한다() {
        // when
        boolean aliceHasNonRequested = userSettlementRepository.existsByUserAndSettlementStatusNot(alice, SettlementStatus.REQUESTED);
        boolean bobHasNonCompleted = userSettlementRepository.existsByUserAndSettlementStatusNot(bob, SettlementStatus.COMPLETED);

        // then
        assertThat(aliceHasNonRequested).isFalse();  // Alice는 REQUESTED만 보유
        assertThat(bobHasNonCompleted).isFalse();    // Bob은 COMPLETED만 보유
    }

    @Test
    void UserSettlement의_상태를_업데이트한다() {
        // when
        userSettlementRepository.updateStatusIfRequested(usAliceRequested.getUserSettlementId(), SettlementStatus.COMPLETED);
        entityManager.flush();
        entityManager.clear();

        // then
        UserSettlement refreshed = userSettlementRepository.findById(usAliceRequested.getUserSettlementId()).orElseThrow();
        assertThat(refreshed.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void 정산ID와_상태로_UserSettlement_목록을_조회한다() {
        // when
        List<UserSettlement> completed = userSettlementRepository
                .findAllBySettlement_SettlementIdAndSettlementStatus(settlement.getSettlementId(), SettlementStatus.COMPLETED);

        // then
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getUser().getUserId()).isEqualTo(bob.getUserId());
    }

    @Test
    void 정산ID로_UserSettlement를_일괄_삭제한다() {
        // when
        userSettlementRepository.deleteAllBySettlementId(settlement.getSettlementId());
        entityManager.flush();
        entityManager.clear();

        // then
        long count = userSettlementRepository.count();
        assertThat(count).isZero();
    }
}
