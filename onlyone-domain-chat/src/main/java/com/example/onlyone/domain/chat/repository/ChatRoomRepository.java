package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom,Long> {
    // 방ID + 모임ID 로 단건 조회
    Optional<ChatRoom> findByChatRoomIdAndClubClubId(Long chatRoomId, Long clubId);

    // 특정 유저 & 특정 모임(club)에서 속해 있는 채팅방 목록 조회 — JOIN FETCH로 N+1 제거
    @Query("""
    SELECT DISTINCT cr FROM ChatRoom cr
    JOIN FETCH cr.club
    LEFT JOIN FETCH cr.schedule
    JOIN cr.userChatRooms ucr
    WHERE ucr.user.userId = :userId AND cr.club.clubId = :clubId
    ORDER BY cr.chatRoomId DESC
    """)
    List<ChatRoom> findChatRoomsByUserIdAndClubId(@Param("userId") Long userId, @Param("clubId") Long clubId);

    // 정기모임(SCHEDULE) 방 단건 조회
    Optional<ChatRoom> findByTypeAndScheduleId(ChatRoomType type, Long scheduleId);

    // 모임 전체 채팅 존재 여부 (중복 생성 방지 등에 활용)
    boolean existsByTypeAndClubClubId(ChatRoomType type, Long clubId);

    // 모임 전체 채팅 조회
    Optional<ChatRoom> findByTypeAndClub_ClubId(ChatRoomType type, Long clubId);
}
