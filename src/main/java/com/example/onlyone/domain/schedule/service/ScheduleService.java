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
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleUserResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import jakarta.validation.Valid;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final UserRepository userRepository;

    /* 스케줄 Status를 READY -> ENDED로 변경하는 스케줄링 */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void updateScheduleStatus() {
        LocalDateTime now = LocalDateTime.now();
        int updatedCount = scheduleRepository.updateExpiredSchedules(
                ScheduleStatus.ENDED,
                ScheduleStatus.READY,
                now
        );
        log.info("✅ {}개의 스케줄 상태가 READY → ENDED로 변경되었습니다.", updatedCount);
    }

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
        User user = userService.getCurrentUser();
        int userCount = userScheduleRepository.countBySchedule(schedule);
        if (userCount >= schedule.getUserLimit()) {
            throw new CustomException(ErrorCode.ALREADY_EXCEEDED_SCHEDULE);
        }
        // 이미 참여한 스케줄인 경우
        if (userScheduleRepository.findByUserAndSchedule(user, schedule).isPresent()) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_SCHEDULE);
        }
        // 이미 종료된 스케줄인 경우
        if (schedule.getScheduleStatus() != ScheduleStatus.READY || schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }
        UserSchedule userSchedule = UserSchedule.builder()
                .user(user)
                .schedule(schedule)
                .scheduleRole(ScheduleRole.MEMBER)
                .build();
        userScheduleRepository.save(userSchedule);
        ChatRoom chatRoom = chatRoomRepository.findByScheduleScheduleIdIdAndClubClubId(schedule.getScheduleId(), clubId)
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
        User user = userService.getCurrentUser();
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        // 이미 종료된 스케줄인 경우
        if (schedule.getScheduleStatus() != ScheduleStatus.READY || schedule.getScheduleTime().isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.ALREADY_ENDED_SCHEDULE);
        }
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        // 리더는 참여 취소 불가능
        if (userSchedule.getScheduleRole() == ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE);
        }
        userScheduleRepository.delete(userSchedule);
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId())
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(user.getUserId(), chatRoom.getChatRoomId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CHAT_ROOM_NOT_FOUND));
        userChatRoomRepository.delete(userChatRoom);
    }

    /* 모임 스케줄 목록 조회 */
    @Transactional(readOnly = true)
    public List<ScheduleResponseDto> getScheduleList(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User currentUser = userService.getCurrentUser();
        return scheduleRepository.findByClubAndScheduleStatusNot(club, ScheduleStatus.CLOSED).stream()
                .map(schedule -> {
                    int userCount = userScheduleRepository.countBySchedule(schedule);
                    Optional<UserSchedule> userScheduleOpt = userScheduleRepository
                            .findByUserAndSchedule(currentUser, schedule);
                    boolean isJoined = userScheduleOpt.isPresent();
                    boolean isLeader = userScheduleOpt
                            .map(userSchedule -> userSchedule.getScheduleRole() == ScheduleRole.LEADER)
                            .orElse(false);
                    long dDay = ChronoUnit.DAYS.between(LocalDate.now(),
                            schedule.getScheduleTime().toLocalDate());
                    return ScheduleResponseDto.from(schedule, userCount, isJoined, isLeader, dDay);
                })
                .collect(Collectors.toList());
    }

    /* 모임 스케줄 참여자 목록 조회 */
    public List<ScheduleUserResponseDto> getScheduleUserList(Long clubId, Long scheduleId) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        return userScheduleRepository.findUsersBySchedule(schedule).stream()
                .map(ScheduleUserResponseDto::from)
                .collect(Collectors.toList());
    }

}
