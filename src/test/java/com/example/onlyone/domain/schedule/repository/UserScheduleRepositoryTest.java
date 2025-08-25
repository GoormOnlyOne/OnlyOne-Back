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
    @Autowired UserClubRepository userClubRepository;

    private Club club;
    private Schedule schedule;

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
    void 특정_유저의_스케줄_참여_여부를_정상적으로_조회한다() {
        // given
        User user = entityManager.getReference(User.class, 1L);
        joinClub(user, club, ClubRole.MEMBER);
        joinSchedule(user, schedule, ScheduleRole.MEMBER);

        // when
        Optional<UserSchedule> userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule);

        // then
        assertThat(userSchedule).isPresent();
        assertThat(userSchedule.get().getUser().getNickname()).isEqualTo("Alice");
        assertThat(userSchedule.get().getSchedule().getScheduleId()).isEqualTo(schedule.getScheduleId());
    }

    @Test
    void 스케줄_참여자_수를_정상적으로_반환한다() {
        // given
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        // when
        int count = userScheduleRepository.countBySchedule(schedule);

        // then
        assertThat(count).isEqualTo(2);
    }

    @Test
    void 스케줄에_속한_UserSchedule_목록을_반환한다() {
        // given
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        // when
        List<UserSchedule> list = userScheduleRepository.findUserSchedulesBySchedule(schedule);

        // then
        assertThat(list).hasSize(2);
        assertThat(list).extracting(us -> us.getUser().getNickname())
                .containsExactlyInAnyOrder("Bob", "Charlie");
    }

    @Test
    void 스케줄에_참여한_User_목록을_반환한다() {
        // given
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        // when
        List<User> users = userScheduleRepository.findUsersBySchedule(schedule);

        // then
        assertThat(users).hasSize(2);
        assertThat(users).extracting(User::getNickname)
                .containsExactlyInAnyOrder("Bob", "Charlie");
    }

    @Test
    void 스케줄의_리더를_Optional로_조회한다() {
        // given
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.LEADER);
        joinSchedule(user1, schedule, ScheduleRole.LEADER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        // when
        Optional<User> found = userScheduleRepository.findLeaderByScheduleAndScheduleRole(
                schedule, ScheduleRole.LEADER);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("Bob");
    }

    @Test
    void 리더가_없으면_Optinal_empty를_반환한다() {
        // given
        User user1 = entityManager.getReference(User.class, 2L);
        User user2 = entityManager.getReference(User.class, 3L);
        joinClub(user1, club, ClubRole.MEMBER);
        joinSchedule(user1, schedule, ScheduleRole.MEMBER);
        joinClub(user2, club, ClubRole.MEMBER);
        joinSchedule(user2, schedule, ScheduleRole.MEMBER);

        // when
        Optional<User> found = userScheduleRepository.findLeaderByScheduleAndScheduleRole(
                schedule, ScheduleRole.LEADER);

        // then
        assertThat(found).isEmpty();
    }
}
