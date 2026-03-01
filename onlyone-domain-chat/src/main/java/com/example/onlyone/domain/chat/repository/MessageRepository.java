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

    /**
     * 네이티브 쿼리 결과를 매핑하는 인터페이스 프로젝션.
     */
    interface LastMessageProjection {
        Long getMessageId();
        Long getChatRoomId();
        Long getUserId();
        String getText();
        LocalDateTime getSentAt();
        boolean getDeleted();
        String getNickname();
        String getProfileImage();
    }

    /**
     * 채팅방들의 마지막 메시지 조회 — derived JOIN + 인터페이스 프로젝션.
     * <p>
     * WHERE IN (서브쿼리)는 MySQL 옵티마이저가 PK 인덱스를 못 타고
     * 풀 테이블 스캔(3M rows)을 발생시킴.
     * derived table JOIN 방식으로 변경하여 PK eq_ref 유도.
     */
    @Query(nativeQuery = true, value = """
        SELECT m.message_id   AS messageId,
               m.chat_room_id AS chatRoomId,
               m.user_id      AS userId,
               m.text          AS text,
               m.sent_at       AS sentAt,
               m.deleted       AS deleted,
               u.nickname      AS nickname,
               u.profile_image AS profileImage
        FROM (
            SELECT MAX(m2.message_id) AS max_id
            FROM message m2
            WHERE m2.chat_room_id IN (:chatRoomIds) AND m2.deleted = false
            GROUP BY m2.chat_room_id
        ) sub
        JOIN message m ON m.message_id = sub.max_id
        JOIN `user` u ON u.user_id = m.user_id
    """)
    List<LastMessageProjection> findLastMessagesByChatRoomIdsNative(@Param("chatRoomIds") List<Long> chatRoomIds);

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