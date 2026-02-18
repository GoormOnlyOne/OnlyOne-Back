package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomCommandService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final UserRepository userRepository;

    @Transactional
    public void deleteChatRoom(Long chatRoomId, Long clubId) {
        ChatRoom chatRoom = chatRoomRepository.findByChatRoomIdAndClubClubId(chatRoomId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        try {
            chatRoomRepository.delete(chatRoom);
            log.info("채팅방 삭제: chatRoomId={}, clubId={}", chatRoomId, clubId);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CHAT_ROOM_DELETE_FAILED);
        }
    }

    @Transactional
    public void joinClubChatRoom(Long clubId, Long userId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        ChatRoom room = chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        User userRef = User.builder().userId(userId).build();

        boolean isMember = userClubRepository.findByUserAndClub(userRef, club).isPresent();
        if (!isMember) throw new CustomException(ErrorCode.CLUB_NOT_JOIN);

        if (userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, room.getChatRoomId())) {
            throw new CustomException(ErrorCode.ALREADY_JOINED);
        }

        userChatRoomRepository.save(UserChatRoom.builder()
                .user(userRef).chatRoom(room).chatRole(ChatRole.MEMBER).build());
    }

    @Transactional
    public void joinScheduleChatRoom(Long scheduleId, Long userId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        ChatRoom room = chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        User userRef = User.builder().userId(userId).build();

        boolean isParticipant = userScheduleRepository.findByUserAndSchedule(userRef, schedule).isPresent();
        if (!isParticipant) throw new CustomException(ErrorCode.SCHEDULE_NOT_JOIN);

        if (userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, room.getChatRoomId())) {
            throw new CustomException(ErrorCode.ALREADY_JOINED);
        }

        userChatRoomRepository.save(UserChatRoom.builder()
                .user(userRef).chatRoom(room).chatRole(ChatRole.MEMBER).build());
    }

    // ── 이벤트 리스너용 메서드 ──

    @Transactional
    public ChatRoom createChatRoom(Club club, ChatRoomType type, Long scheduleId) {
        ChatRoom.ChatRoomBuilder builder = ChatRoom.builder()
                .club(club)
                .type(type);
        if (type == ChatRoomType.SCHEDULE) {
            builder.scheduleId(scheduleId);
        }
        ChatRoom saved = chatRoomRepository.save(builder.build());
        log.info("채팅방 생성: chatRoomId={}, type={}, clubId={}", saved.getChatRoomId(), type, club.getClubId());
        return saved;
    }

    @Transactional
    public void addMember(ChatRoom chatRoom, User user, ChatRole role) {
        userChatRoomRepository.save(UserChatRoom.builder()
                .chatRoom(chatRoom).user(user).chatRole(role).build());
    }

    @Transactional
    public void removeMember(Long userId, Long chatRoomId) {
        UserChatRoom userChatRoom = userChatRoomRepository
                .findByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "UserChatRoom not found: userId=" + userId + ", chatRoomId=" + chatRoomId));
        userChatRoomRepository.delete(userChatRoom);
    }

    @Transactional
    public void deleteChatRoomBySchedule(Long scheduleId) {
        ChatRoom chatRoom = chatRoomRepository
                .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ChatRoom not found for scheduleId: " + scheduleId));
        chatRoomRepository.delete(chatRoom);
    }
}
