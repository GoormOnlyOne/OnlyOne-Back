package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.UserChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserChatRoomRepository extends JpaRepository<UserChatRoom,Long> {
    //특정 사용자의 특정 채팅방 참여 정보 단일 조회
    Optional<UserChatRoom> findByUserUserIdAndChatRoomChatRoomId(Long userId, Long chatRoomId);

    //특정 사용자가 특정 채팅방에 속해 있는지 확인
    boolean existsByUserUserIdAndChatRoomChatRoomId(Long userId, Long chatRoomId);

    /** 특정 모임의 모든 채팅방에서 해당 사용자의 참여 정보 삭제 (모임 탈퇴 시) */
    @Modifying
    @Query("DELETE FROM UserChatRoom ucr WHERE ucr.user.userId = :userId AND ucr.chatRoom.club.clubId = :clubId")
    int deleteByUserIdAndClubId(@Param("userId") Long userId, @Param("clubId") Long clubId);
}