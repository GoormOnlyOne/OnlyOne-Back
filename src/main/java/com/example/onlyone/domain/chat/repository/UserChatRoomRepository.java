package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserChatRoomRepository extends JpaRepository<UserChatRoom,Long> {
    //특정 사용자가 특정 채팅방에 속해 있는지 확인
    Optional<UserChatRoom> findByUserUserIdAndChatRoomChatRoomId(Long userId, Long chatRoomId);

    /**
     //채팅방 기준 전체 사용자 목록 조회
     List<UserChatRoom> findAllByChatRoomChatRoomId(Long chatRoomId);
     */

    // 특정 유저 & 특정 모임(club)에서 속해 있는 채팅방 목록 조회
    List<UserChatRoom> findAllByUserUserIdAndChatRoomClubClubId(Long userId, Long clubId);

    Optional<UserChatRoom> findByUserAndChatRoom(User user, ChatRoom chatRoom);
}


