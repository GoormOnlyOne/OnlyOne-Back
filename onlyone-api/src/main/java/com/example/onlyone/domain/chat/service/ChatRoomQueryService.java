package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.port.ChatMessageStoragePort;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
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
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    @Cacheable(value = "chatRooms",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #clubId")
    public List<ChatRoomResponse> getChatRoomsUserJoinedInClub(Long clubId) {
        Long userId = userService.getCurrentUserId();

        // existsById + existsBy 2쿼리 → userClub 단일 조회로 통합
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            // club 미존재 or 미가입 모두 동일 에러 (별도 existsById 쿼리 제거)
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
