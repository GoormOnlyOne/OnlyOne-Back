package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message,Long> {
    // 채팅방들의 마지막 메시지 조회 (상관 서브쿼리로 각 방의 MAX messageId 사용)
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.user
        WHERE m.deleted = false
          AND m.chatRoom.chatRoomId IN :chatRoomIds
          AND m.messageId IN (
              SELECT MAX(m2.messageId) FROM Message m2
              WHERE m2.chatRoom.chatRoomId IN :chatRoomIds AND m2.deleted = false
              GROUP BY m2.chatRoom.chatRoomId
          )
    """)
    List<Message> findLastMessagesByChatRoomIds(@Param("chatRoomIds") List<Long> chatRoomIds);

    // 최신 N건 (초기 로드) — JOIN FETCH로 N+1 제거
    @Query("""
       select m from Message m
       join fetch m.chatRoom
       join fetch m.user
       where m.chatRoom.chatRoomId = :roomId
         and m.deleted = false
       order by m.sentAt desc, m.messageId desc
    """)
    List<Message> findLatest(@Param("roomId") Long roomId, Pageable pageable);

    // 커서 기준 더 '이전'(과거) N건 — JOIN FETCH로 N+1 제거
    @Query("""
       select m from Message m
       join fetch m.chatRoom
       join fetch m.user
       where m.chatRoom.chatRoomId = :roomId
         and m.deleted = false
         and (m.sentAt < :cursorAt or (m.sentAt = :cursorAt and m.messageId < :cursorId))
       order by m.sentAt desc, m.messageId desc
    """)
    List<Message> findOlderThan(@Param("roomId") Long roomId,
                                @Param("cursorAt") LocalDateTime cursorAt,
                                @Param("cursorId") Long cursorId,
                                Pageable pageable);


}