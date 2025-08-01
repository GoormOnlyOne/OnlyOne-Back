package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message,Long> {
    //채팅방(chatRoom) 내 모든 메시지 조회 (발송시간 오름차순 / 삭제되지 않은 메시지만)
    List<Message> findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(Long chatRoomId);

    @Modifying
    @Query("UPDATE Message m " +
            "SET m.text = '삭제된 메시지입니다.', m.deleted = true " +
            "WHERE m.messageId = :messageId AND m.user.userId = :userId")
    int softDeleteByUser(@Param("messageId") Long messageId, @Param("userId") Long userId);
}