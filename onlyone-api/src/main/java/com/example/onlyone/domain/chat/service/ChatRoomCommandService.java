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
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.chat.exception.ChatErrorCode;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.schedule.exception.ScheduleErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.GlobalErrorCode;
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
    private final UserScheduleRepository userScheduleRepository;

    // ── API 메서드 ──

    @Transactional
    public void deleteChatRoom(Long chatRoomId, Long clubId) {
        ChatRoom chatRoom = chatRoomRepository.findByChatRoomIdAndClubClubId(chatRoomId, clubId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        try {
            chatRoomRepository.delete(chatRoom);
            log.info("채팅방 삭제: chatRoomId={}, clubId={}", chatRoomId, clubId);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ChatErrorCode.CHAT_ROOM_DELETE_FAILED);
        }
    }

    @Transactional
    public void joinClubChatRoom(Long clubId, Long userId) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_FOUND);
        }
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_JOIN);
        }

        ChatRoom room = chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        validateNotAlreadyJoined(userId, room.getChatRoomId());
        saveMember(userId, room, ChatRole.MEMBER);
    }

    @Transactional
    public void joinScheduleChatRoom(Long scheduleId, Long userId) {
        if (!userScheduleRepository.existsByUser_UserIdAndSchedule_ScheduleId(userId, scheduleId)) {
            throw new CustomException(ScheduleErrorCode.SCHEDULE_NOT_JOIN);
        }

        ChatRoom room = chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        validateNotAlreadyJoined(userId, room.getChatRoomId());
        saveMember(userId, room, ChatRole.MEMBER);
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
                .orElseThrow(() -> new CustomException(ChatErrorCode.USER_CHAT_ROOM_NOT_FOUND));
        userChatRoomRepository.delete(userChatRoom);
    }

    @Transactional
    public void deleteChatRoomBySchedule(Long scheduleId) {
        ChatRoom chatRoom = chatRoomRepository
                .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        chatRoomRepository.delete(chatRoom);
    }

    // ── private ──

    private void validateNotAlreadyJoined(Long userId, Long chatRoomId) {
        if (userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId)) {
            throw new CustomException(GlobalErrorCode.ALREADY_JOINED);
        }
    }

    private void saveMember(Long userId, ChatRoom room, ChatRole role) {
        User userRef = User.builder().userId(userId).build();
        userChatRoomRepository.save(UserChatRoom.builder()
                .user(userRef).chatRoom(room).chatRole(role).build());
    }
}
