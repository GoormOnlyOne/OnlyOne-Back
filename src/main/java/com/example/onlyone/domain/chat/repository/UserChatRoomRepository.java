package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.UserChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserChatRoomRepository extends JpaRepository<UserChatRoom,Long> {
    //특정 사용자의 특정 채팅방 참여 정보 단일 조회
    Optional<UserChatRoom> findByUserUserIdAndChatRoomChatRoomId(Long userId, Long chatRoomId);

    //특정 사용자가 특정 채팅방에 속해 있는지 확인
    boolean existsByUserUserIdAndChatRoomChatRoomId(Long userId, Long chatRoomId);

    List<UserChatRoom> findAllByChatRoomId(Long chatRoomId);
}