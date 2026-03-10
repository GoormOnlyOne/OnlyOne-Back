package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.dto.response.ScheduleDetailResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleListRow;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleUserResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.schedule.exception.ScheduleErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 일정(Schedule) 쿼리 서비스 — 목록·상세·참여자 조회
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ScheduleQueryService {

    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final ClubRepository clubRepository;
    private final UserService userService;

    /** 모임 스케줄 목록 조회 */
    @Cacheable(value = "scheduleList",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #clubId")
    public List<ScheduleResponseDto> getScheduleList(Long clubId) {
        Club club = findClubOrThrow(clubId);
        User currentUser = userService.getCurrentUser();

        return scheduleRepository.findScheduleListWithUserInfo(club, currentUser).stream()
                .map(ScheduleListRow::from)
                .map(ScheduleResponseDto::from)
                .toList();
    }

    /** 모임 스케줄 참여자 목록 조회 */
    @Cacheable(value = "scheduleUsers", key = "#clubId + '_' + #scheduleId")
    public List<ScheduleUserResponseDto> getScheduleUserList(Long clubId, Long scheduleId) {
        List<User> users = userScheduleRepository.findUsersByScheduleIdAndClubId(scheduleId, clubId);
        return users.stream()
                .map(ScheduleUserResponseDto::from)
                .toList();
    }

    /** 스케줄 정보 상세 조회 */
    @Cacheable(value = "scheduleDetail", key = "#clubId + '_' + #scheduleId")
    public ScheduleDetailResponseDto getScheduleDetails(Long clubId, Long scheduleId) {
        Schedule schedule = scheduleRepository.findByIdAndClubId(scheduleId, clubId)
                .orElseThrow(() -> new CustomException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

        return ScheduleDetailResponseDto.from(schedule);
    }

    private Club findClubOrThrow(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ClubErrorCode.CLUB_NOT_FOUND));
    }
}
