package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserService userService;

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
}