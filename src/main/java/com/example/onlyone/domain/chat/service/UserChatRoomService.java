package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserChatRoomService {

    private final UserChatRoomRepository userChatRoomRepository;

    // 유저가 해당 채팅방에 참여 중인지 확인
    public boolean isUserInChatRoom(Long userId, Long chatRoomId) {
        return userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId).isPresent();
    }
}
