package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
class ScheduleRepositoryTest {

    @Autowired
    ScheduleRepository scheduleRepository;
    @Autowired
    ClubRepository clubRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EntityManager entityManager;

    @Test
    void 스케줄_상태가_READY인_것들_중_과거_시간인_것만_END로_변경된다() {
        Interest interest = entityManager.getReference(Interest.class, 1L);
        Club club = clubRepository.save(
                Club.builder()
                        .name("온리원 첫 번째 모임")
                        .userLimit(10)
                        .description("테스트 설명...")
                        .city("서울특별시")
                        .district("강남구")
                        .interest(interest)
                        .build()
        );

        scheduleRepository.save(Schedule.builder()
                .club(club)
                .name("과거 스케줄")
                .location("장소")
                .cost(1000)
                .userLimit(10)
                .scheduleStatus(ScheduleStatus.READY)
                .scheduleTime(LocalDateTime.now().minusHours(1))
                .build());

        scheduleRepository.save(Schedule.builder()
                .club(club)
                .name("미래 스케줄")
                .location("장소")
                .cost(1000)
                .userLimit(10)
                .scheduleStatus(ScheduleStatus.READY)
                .scheduleTime(LocalDateTime.now().plusHours(1))
                .build());

        entityManager.flush();
        entityManager.clear();

        int updated = scheduleRepository.updateExpiredSchedules(
                ScheduleStatus.ENDED, ScheduleStatus.READY, LocalDateTime.now());

        assertThat(updated).isEqualTo(1);
    }


}
