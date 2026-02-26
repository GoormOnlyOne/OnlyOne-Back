package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserScheduleRepository extends JpaRepository<UserSchedule,Long> {
    boolean existsByUser_UserIdAndSchedule_ScheduleId(Long userId, Long scheduleId);
    Optional<UserSchedule> findByUserAndSchedule(User user, Schedule schedule);
    int countBySchedule(Schedule schedule);
    List<UserSchedule> findUserSchedulesBySchedule(Schedule schedule);

    @Query("SELECT us.user FROM UserSchedule us WHERE us.schedule = :schedule")
    List<User> findUsersBySchedule(@Param("schedule") Schedule schedule);

    @Query("SELECT us.user FROM UserSchedule us WHERE us.schedule = :schedule AND us.scheduleRole = :role")
    Optional<User> findLeaderByScheduleAndScheduleRole(@Param("schedule") Schedule schedule, @Param("role") ScheduleRole role);

    /** leaveSchedule 최적화: schedule + userSchedule을 단일 쿼리로 조회 */
    @Query("SELECT us FROM UserSchedule us " +
           "JOIN FETCH us.schedule s " +
           "WHERE us.user = :user AND s.scheduleId = :scheduleId")
    Optional<UserSchedule> findByUserAndScheduleIdWithSchedule(@Param("user") User user, @Param("scheduleId") Long scheduleId);

    /** deleteSchedule 최적화: Lazy loading 없이 멤버 userId 목록 직접 조회 */
    @Query("SELECT us.user.userId FROM UserSchedule us WHERE us.schedule = :schedule AND us.scheduleRole = :role")
    List<Long> findMemberUserIdsByScheduleAndRole(@Param("schedule") Schedule schedule, @Param("role") ScheduleRole role);

    /** getScheduleUserList 최적화: scheduleId + clubId로 직접 User 조회 (3쿼리 → 1쿼리) */
    @Query("SELECT us.user FROM UserSchedule us " +
           "WHERE us.schedule.scheduleId = :scheduleId AND us.schedule.club.clubId = :clubId")
    List<User> findUsersByScheduleIdAndClubId(@Param("scheduleId") Long scheduleId, @Param("clubId") Long clubId);

}
