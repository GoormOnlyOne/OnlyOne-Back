package com.example.onlyone.domain.schedule.fixture;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;

public final class ScheduleFixtures {

    private ScheduleFixtures() {}

    public static final LocalDateTime FUTURE_TIME = LocalDateTime.now().plusDays(7);

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

    // ==================== Club ====================

    public static Club club() {
        return Club.builder()
                .clubId(1L)
                .name("테스트 모임")
                .userLimit(10)
                .description("테스트 설명")
                .city("서울특별시")
                .district("강남구")
                .build();
    }

    // ==================== Schedule ====================

    public static Schedule schedule(Club club) {
        return schedule(1L, club);
    }

    public static Schedule schedule(Long id, Club club) {
        return Schedule.builder()
                .scheduleId(id)
                .name("정기 모임")
                .location("구름스퀘어 강남")
                .cost(10000L)
                .userLimit(10)
                .scheduleTime(FUTURE_TIME)
                .scheduleStatus(ScheduleStatus.READY)
                .club(club)
                .userSchedules(new ArrayList<>())
                .build();
    }

    public static Schedule endedSchedule(Long id, Club club) {
        return Schedule.builder()
                .scheduleId(id)
                .name("종료된 모임")
                .location("서울")
                .cost(10000L)
                .userLimit(10)
                .scheduleTime(FUTURE_TIME)
                .scheduleStatus(ScheduleStatus.ENDED)
                .club(club)
                .userSchedules(new ArrayList<>())
                .build();
    }

    public static Schedule expiredSchedule(Long id, Club club) {
        return Schedule.builder()
                .scheduleId(id)
                .name("만료된 모임")
                .location("서울")
                .cost(5000L)
                .userLimit(10)
                .scheduleTime(LocalDateTime.now().minusDays(1))
                .scheduleStatus(ScheduleStatus.READY)
                .club(club)
                .build();
    }

    // ==================== UserClub ====================

    public static UserClub leaderUserClub(User leader, Club club) {
        return UserClub.builder()
                .userClubId(1L)
                .user(leader)
                .club(club)
                .clubRole(ClubRole.LEADER)
                .build();
    }

    public static UserClub memberUserClub(User member, Club club) {
        return UserClub.builder()
                .userClubId(2L)
                .user(member)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
    }

    // ==================== UserSchedule ====================

    public static UserSchedule leaderUserSchedule(User leader, Schedule schedule) {
        return UserSchedule.builder()
                .userScheduleId(1L)
                .user(leader)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.LEADER)
                .build();
    }

    public static UserSchedule memberUserSchedule(User member, Schedule schedule) {
        return UserSchedule.builder()
                .userScheduleId(2L)
                .user(member)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();
    }

    // ==================== Request DTO ====================

    public static ScheduleRequestDto requestDto() {
        return new ScheduleRequestDto("정기 모임", "구름스퀘어 강남", 10000L, 10, FUTURE_TIME);
    }

    public static ScheduleRequestDto updateRequestDto() {
        return new ScheduleRequestDto("수정된 정기 모임", "역삼역", 10000L, 20, FUTURE_TIME.plusDays(1));
    }

    public static ScheduleRequestDto costChangeRequestDto(Long newCost) {
        return new ScheduleRequestDto("정기 모임", "구름스퀘어 강남", newCost, 10, FUTURE_TIME);
    }
}
