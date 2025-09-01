package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final MessageRepository messageRepository;
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;

    // 채팅방 삭제
    @Transactional
    public void deleteChatRoom(Long chatRoomId, Long clubId) {
        ChatRoom chatRoom = chatRoomRepository.findByChatRoomIdAndClubClubId(chatRoomId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        try {
            chatRoomRepository.delete(chatRoom);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CHAT_ROOM_DELETE_FAILED);
        }
    }

    // 유저가 특정 모임(club)의 어떤 채팅방들에 참여하고 있는지 조회
    public List<ChatRoomResponse> getChatRoomsUserJoinedInClub(Long clubId) {
        User user = userService.getCurrentUser();
        Long userId = user.getUserId();

        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND)); // 404

        Optional<UserClub> userClub = userClubRepository.findByUserAndClub(user, club);
        if (userClub.isEmpty()) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }

        List<ChatRoom> chatRooms = chatRoomRepository.findChatRoomsByUserIdAndClubId(userId, clubId);
        List<Long> chatRoomIds = chatRooms.stream()
                .map(ChatRoom::getChatRoomId)
                .toList();

        // 마지막 메시지를 한 번에 조회
        List<Message> lastMessages = messageRepository.findLastMessagesByChatRoomIds(chatRoomIds);
        Map<Long, Message> lastMessageMap = lastMessages.stream()
                .collect(Collectors.toMap(
                        m -> m.getChatRoom().getChatRoomId(),
                        Function.identity()
                ));

        return chatRooms.stream()
                .map(chatRoom -> {
                    Message lastMessage = lastMessageMap.get(chatRoom.getChatRoomId());
                    return ChatRoomResponse.from(chatRoom, lastMessage);
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public void joinClubChatRoom(Long clubId, Long userId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        ChatRoom room = chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        User userRef = User.builder().userId(userId).build();

        boolean isMember = userClubRepository.findByUserAndClub(userRef, club).isPresent();
        if (!isMember) throw new CustomException(ErrorCode.CLUB_NOT_JOIN);

        if (userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, room.getChatRoomId())) {
            throw new CustomException(ErrorCode.ALREADY_JOINED);
        }

        userChatRoomRepository.save(UserChatRoom.builder()
                .user(userRef)
                .chatRoom(room)
                .build());
    }

    @Transactional
    public void joinScheduleChatRoom(Long scheduleId, Long userId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        ChatRoom room = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        User userRef = User.builder().userId(userId).build();

        boolean isParticipant = userScheduleRepository.findByUserAndSchedule(userRef, schedule).isPresent();
        if (!isParticipant) throw new CustomException(ErrorCode.SCHEDULE_NOT_JOIN);

        if (userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, room.getChatRoomId())) {
            throw new CustomException(ErrorCode.ALREADY_JOINED);
        }

        userChatRoomRepository.save(UserChatRoom.builder()
                .user(userRef)
                .chatRoom(room)
                .build());
    }
}