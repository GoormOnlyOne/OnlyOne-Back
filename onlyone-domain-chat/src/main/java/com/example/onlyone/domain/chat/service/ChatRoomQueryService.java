package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.port.ChatMessageStoragePort;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomQueryService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageStoragePort chatMessageStoragePort;
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    public List<ChatRoomResponse> getChatRoomsUserJoinedInClub(Long clubId) {
        Long userId = userService.getCurrentUserId();

        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_FOUND);
        }
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_JOIN);
        }

        List<ChatRoom> chatRooms = chatRoomRepository.findChatRoomsByUserIdAndClubId(userId, clubId);
        Map<Long, ChatMessageItemDto> lastMessageMap = findLastMessages(chatRooms);

        return chatRooms.stream()
                .map(room -> ChatRoomResponse.from(room, lastMessageMap.get(room.getChatRoomId())))
                .toList();
    }

    private Map<Long, ChatMessageItemDto> findLastMessages(List<ChatRoom> chatRooms) {
        List<Long> chatRoomIds = chatRooms.stream()
                .map(ChatRoom::getChatRoomId)
                .toList();

        if (chatRoomIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return chatMessageStoragePort.findLastMessagesByChatRoomIds(chatRoomIds).stream()
                .collect(Collectors.toMap(
                        ChatMessageItemDto::chatRoomId,
                        Function.identity()));
    }
}
