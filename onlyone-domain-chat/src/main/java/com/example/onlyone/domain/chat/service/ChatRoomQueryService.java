package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomQueryService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    public List<ChatRoomResponse> getChatRoomsUserJoinedInClub(Long clubId) {
        User user = userService.getCurrentUser();
        Long userId = user.getUserId();

        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_JOIN));

        List<ChatRoom> chatRooms = chatRoomRepository.findChatRoomsByUserIdAndClubId(userId, clubId);
        List<Long> chatRoomIds = chatRooms.stream()
                .map(ChatRoom::getChatRoomId)
                .toList();

        List<Message> lastMessages = messageRepository.findLastMessagesByChatRoomIds(chatRoomIds);
        Map<Long, Message> lastMessageMap = lastMessages.stream()
                .collect(Collectors.toMap(
                        m -> m.getChatRoom().getChatRoomId(),
                        Function.identity()
                ));

        return chatRooms.stream()
                .map(chatRoom -> ChatRoomResponse.from(
                        chatRoom, lastMessageMap.get(chatRoom.getChatRoomId())))
                .toList();
    }
}
