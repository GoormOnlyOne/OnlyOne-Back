package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.onlyone.domain.chat.entity.Type;
import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom,Long> {
    // 방ID + 모임ID 로 단건 조회
    Optional<ChatRoom> findByChatRoomIdAndClubClubId(Long chatRoomId, Long clubId);

    // 특정 유저 & 특정 모임(club)에서 속해 있는 채팅방 목록 조회
    @Query("""
    SELECT ucr.chatRoom
    FROM UserChatRoom ucr
    WHERE ucr.user.userId = :userId AND ucr.chatRoom.club.clubId = :clubId
    """)
    List<ChatRoom> findChatRoomsByUserIdAndClubId(@Param("userId") Long userId, @Param("clubId") Long clubId);

    // 정기 모임 관련 채팅 목록 중 정기 모임
    Optional<ChatRoom> findByTypeAndScheduleId(Type type, Long scheduleId);
}
