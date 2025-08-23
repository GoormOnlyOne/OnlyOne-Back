package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
class UserScheduleRepositoryTest {

    @Autowired UserScheduleRepository userScheduleRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private Club club;
    private Schedule schedule;
    @Autowired
    private UserClubRepository userClubRepository;

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

        schedule = scheduleRepository.save(
                Schedule.builder()
                        .club(club)
                        .name("테스트 스케줄")
                        .location("장소")
                        .cost(1000)
                        .userLimit(10)
                        .scheduleStatus(ScheduleStatus.READY)
                        .scheduleTime(LocalDateTime.now().plusDays(1))
                        .build()
        );
        entityManager.flush();
        entityManager.clear();
    }

    private UserClub joinClub(User user, Club club, ClubRole clubRole) {
        UserClub uc = userClubRepository.save(
                UserClub.builder()
                        .user(user)
                        .club(club)
                        .clubRole(clubRole)
                        .build()
        );
        entityManager.flush();
        entityManager.clear();
        return uc;
    }

    private UserSchedule joinSchedule(User user, Schedule schedule, ScheduleRole role) {
        UserSchedule us = userScheduleRepository.save(
                UserSchedule.builder()
                        .user(user)
                        .schedule(schedule)
                        .scheduleRole(role)
                        .build()
        );
        entityManager.flush();
        entityManager.clear();
        return us;
    }

    @Test
    @DisplayName("findByUserAndSchedule: 특정 유저의 특정 스케줄 참여 여부를 조회한다")
    void findByUserAndSchedule_success() {
        User user = entityManager.getReference(User.class, 1L);
        joinClub(user, club, ClubRole.MEMBER);
        joinSchedule(user, schedule, ScheduleRole.MEMBER);

        Optional<UserSchedule> userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule);

        assertThat(userSchedule).isPresent();
        assertThat(userSchedule.get().getUser().getNickname()).isEqualTo("Alice");
        assertThat(userSchedule.get().getSchedule().getScheduleId()).isEqualTo(schedule.getScheduleId());
    }

    @Test
    @DisplayName("countBySchedule: 스케줄 참가자 수를 반환한다")
    void countBySchedule_success() {
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        int count = userScheduleRepository.countBySchedule(schedule);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("findUserSchedulesBySchedule: 스케줄에 속한 UserSchedule 목록을 반환한다")
    void findUserSchedulesBySchedule_success() {
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        List<UserSchedule> list = userScheduleRepository.findUserSchedulesBySchedule(schedule);

        assertThat(list).hasSize(2);
        assertThat(list).extracting(us -> us.getUser().getNickname())
                .containsExactlyInAnyOrder("Bob", "Charlie");
    }

    @Test
    @DisplayName("findUsersBySchedule(JPQL): 스케줄에 참여한 User 목록을 반환한다")
    void findUsersBySchedule_success() {
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        List<User> users = userScheduleRepository.findUsersBySchedule(schedule);

        assertThat(users).hasSize(2);
        assertThat(users).extracting(User::getNickname)
                .containsExactlyInAnyOrder("Bob", "Charlie");
    }

    @Test
    @DisplayName("findLeaderByScheduleAndScheduleRole: 리더를 Optional로 조회한다(있으면 반환)")
    void findLeader_success() {
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.LEADER);
        joinSchedule(user1, schedule, ScheduleRole.LEADER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        Optional<User> found = userScheduleRepository.findLeaderByScheduleAndScheduleRole(
                schedule, ScheduleRole.LEADER);

        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("findLeaderByScheduleAndScheduleRole: 리더가 없으면 Optional.empty()")
    void findLeader_empty_when_no_leader() {
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        Optional<User> found = userScheduleRepository.findLeaderByScheduleAndScheduleRole(
                schedule, ScheduleRole.LEADER);

        assertThat(found).isEmpty();
    }
}
