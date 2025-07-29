package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
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
    private final UserChatRoomRepository userChatRoomRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClubRepository clubRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserService userService;

    /* 정기 모임 생성*/
    public void createSchedule(Long clubId, @Valid ScheduleRequestDto requestDto) {
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
        UserChatRoom userChatRoom = UserChatRoom.builder()
                .user(user)
                .chatRoom(chatRoom)
                .chatRole(ChatRole.LEADER)
                .build();
        userChatRoomRepository.save(userChatRoom);
    }

    /* 정기 모임 수정 */
    public void updateSchedule(Long clubId, Long scheduleId, @Valid ScheduleRequestDto requestDto) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        User user = userService.getCurrentUser();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        if (userSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }
        schedule.update(requestDto.getName(), requestDto.getLocation(), requestDto.getCost(), requestDto.getUserLimit(), requestDto.getScheduleTime());
    }

    /* 정기 모임 참여 */
    public void joinSchedule(Long clubId, Long scheduleId) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
//        User user = userService.getCurrentUser();
        User user = userService.getAnotherUser();
        if (userScheduleRepository.findByUserAndSchedule(user, schedule).isPresent()) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_SCHEDULE);
        }
        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();
        userScheduleRepository.save(userSchedule);
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId())
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        UserChatRoom userChatRoom = UserChatRoom.builder()
                .user(user)
                .chatRoom(chatRoom)
                .chatRole(ChatRole.MEMBER)
                .build();
        userChatRoomRepository.save(userChatRoom);
    }

    /* 정기 모임 참여 취소 */
    public void leaveSchedule(Long clubId, Long scheduleId) {
//        User user = userService.getCurrentUser();
        User user = userService.getAnotherUser();
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        if (userSchedule.getScheduleRole() == ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }
        userScheduleRepository.delete(userSchedule);
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId())
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserAndChatRoom(user, chatRoom)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CHAT_ROOM_NOT_FOUND));
        userChatRoomRepository.delete(userChatRoom);
    }
}
