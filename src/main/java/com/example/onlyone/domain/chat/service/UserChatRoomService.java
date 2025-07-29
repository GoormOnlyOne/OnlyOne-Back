package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserChatRoomService {

    private final UserChatRoomRepository userChatRoomRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    // 채팅방 참여
    @Transactional
    public UserChatRoom joinChatRoom(Long userId, Long chatRoomId, Role role) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다. ID: " + chatRoomId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다. ID: " + userId));

        // 이미 참여 중인지 확인
        if (userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId).isPresent()) {
            throw new IllegalArgumentException("이미 채팅방에 참여 중입니다. userId: " + userId + ", chatRoomId: " + chatRoomId);
        }

        UserChatRoom userChatRoom = new UserChatRoom();
        userChatRoom.setUser(user);
        userChatRoom.setChatRoom(chatRoom);
        userChatRoom.setRole(role);

        return userChatRoomRepository.save(userChatRoom);
    }

    // 채팅방 나가기
    @Transactional
    public void leaveChatRoom(Long userId, Long chatRoomId) {
        UserChatRoom userChatRoom = userChatRoomRepository
                .findByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방에 참여하고 있지 않습니다. userId: " + userId + ", chatRoomId: " + chatRoomId));

        userChatRoomRepository.delete(userChatRoom);
    }

    // 유저가 해당 채팅방에 참여 중인지 확인
    public boolean isUserInChatRoom(Long userId, Long chatRoomId) {
        return userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId).isPresent();
    }
}
