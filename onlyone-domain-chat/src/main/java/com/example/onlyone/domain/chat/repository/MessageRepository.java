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
    //채팅방들의 마지막 메세지들 조회 (GROUP BY + JOIN으로 상관 서브쿼리 제거)
    @Query(value = """
        SELECT m.* FROM message m
        INNER JOIN (
            SELECT chat_room_id, MAX(sent_at) AS max_sent_at
            FROM message
            WHERE chat_room_id IN :chatRoomIds AND deleted = 0
            GROUP BY chat_room_id
        ) latest ON m.chat_room_id = latest.chat_room_id AND m.sent_at = latest.max_sent_at
        WHERE m.deleted = 0
    """, nativeQuery = true)
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