package com.example.onlyone.domain.schedule.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule,Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.scheduleId = :scheduleId")
    Optional<Schedule> findByIdWithLock(@Param("scheduleId") Long scheduleId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Schedule s SET s.scheduleStatus = :endedStatus WHERE s.scheduleStatus = :readyStatus AND s.scheduleTime < :now")
    int updateExpiredSchedules(@Param("endedStatus") ScheduleStatus endedStatus,
                               @Param("readyStatus") ScheduleStatus readyStatus,
                               @Param("now") LocalDateTime now);

    /** 자정 배치: 상태 변경 전 만료 대상 스케줄 조회 (이벤트 발행용) */
    @Query("SELECT s FROM Schedule s JOIN FETCH s.club WHERE s.scheduleStatus = :status AND s.scheduleTime < :now")
    List<Schedule> findExpiredSchedules(@Param("status") ScheduleStatus status, @Param("now") LocalDateTime now);

    /**
     * N+1 해결: 스케줄 목록 + 참여자 수 + 현재 유저 참여 상태를 단일 쿼리로 조회
     * 기존: 1 (findAll) + N (countBySchedule) + N (findByUserAndSchedule) = 1+2N 쿼리
     * 변경: 1 쿼리
     */
    @Query("SELECT s, " +
           "(SELECT COUNT(us2) FROM UserSchedule us2 WHERE us2.schedule = s), " +
           "us.scheduleRole " +
           "FROM Schedule s " +
           "LEFT JOIN UserSchedule us ON us.schedule = s AND us.user = :currentUser " +
           "WHERE s.club = :club " +
           "ORDER BY s.scheduleTime DESC")
    List<Object[]> findScheduleListWithUserInfo(@Param("club") Club club, @Param("currentUser") User currentUser);

    List<Schedule> findAllByClubOrderByScheduleTimeDesc(Club club);

    List<Schedule> findAllByClub(Club club);

    Optional<Schedule> findByNameAndClub_ClubId(String name, Long clubId);

    /** getScheduleDetails 최적화: club 존재 검증 + schedule 조회를 단일 쿼리로 */
    @Query("SELECT s FROM Schedule s WHERE s.scheduleId = :scheduleId AND s.club.clubId = :clubId")
    Optional<Schedule> findByIdAndClubId(@Param("scheduleId") Long scheduleId, @Param("clubId") Long clubId);
}
