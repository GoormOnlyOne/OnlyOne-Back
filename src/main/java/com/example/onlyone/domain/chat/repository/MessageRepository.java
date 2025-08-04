package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message,Long> {
    //채팅방(chatRoom) 내 모든 메시지 조회 (발송시간 오름차순 / 삭제되지 않은 메시지만)
    List<Message> findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(Long chatRoomId);

    //채팅방들의 마지막 메세지들 조회
    @Query("""
    SELECT m FROM Message m
    WHERE m.chatRoom.chatRoomId IN :chatRoomIds
      AND m.deleted = false
      AND m.sentAt = (
        SELECT MAX(m2.sentAt) FROM Message m2
        WHERE m2.chatRoom.chatRoomId = m.chatRoom.chatRoomId
          AND m2.deleted = false
      )
    """)
    List<Message> findLastMessagesByChatRoomIds(@Param("chatRoomIds") List<Long> chatRoomIds);

}