package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.dto.request.ScheduleCreateRequestDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class ScheduleService {
    private final UserScheduleRepository userScheduleRepository;
    private ScheduleRepository scheduleRepository;
    private ClubRepository clubRepository;
    private ChatRoomRepository chatRoomRepository;
    private final UserService userService;

    /* 정기 모임 생성*/
    public void createSchedule(Long clubId, @Valid ScheduleCreateRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = requestDto.toEntity(club);
        scheduleRepository.save(schedule);
        User user = userService.getCurrentUser();
        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.LEADER)
                .build();
        userScheduleRepository.save(userSchedule);
        ChatRoom chatRoom = ChatRoom.builder()
                .club(club)
                .scheduleId(schedule.getScheduleId())
                .type(Type.SCHEDULE)
                .build();
        chatRoomRepository.save(chatRoom);
    }
}
