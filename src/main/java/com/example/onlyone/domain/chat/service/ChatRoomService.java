package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    //private final UserRepository userRepository;
    //private final ClubRepository clubRepository;

    // 채팅방 삭제
    @Transactional
    public void deleteChatRoom(Long chatRoomId, Long clubId) {
        ChatRoom chatRoom = chatRoomRepository.findByChatRoomIdAndClubClubId(chatRoomId, clubId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Chat room with id " + chatRoomId + " not found in club " + clubId));

        chatRoomRepository.delete(chatRoom);
    }

    /**
    // 채팅방 참여자 목록 조회
    public List<UserChatRoom> getChatRoomUsers(Long chatRoomId) {
        return userChatRoomRepository.findAllByChatRoomChatRoomId(chatRoomId);
    }
    */

    // 유저가 특정 모임(club)의 어떤 채팅방들에 참여하고 있는지 조회
    public List<ChatRoom> getChatRoomsUserJoinedInClub(Long userId, Long clubId) {
        // 클럽 존재 유무 체크 (optional)
        // Club club = clubRepository.findById(clubId)
        //     .orElseThrow(() -> new IllegalArgumentException("Club not found"));

        List<UserChatRoom> userChatRooms =
                userChatRoomRepository.findAllByUserUserIdAndChatRoomClubClubId(userId, clubId);

        return userChatRooms.stream()
                .map(UserChatRoom::getChatRoom)
                .collect(Collectors.toList());
    }

    //채팅방 단건 조회
    public ChatRoom getById(Long chatRoomId) {
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다. ID: " + chatRoomId));
    }
}