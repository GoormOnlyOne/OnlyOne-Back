package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message,Long> {
    //채팅방(chatRoom) 내 모든 메시지 조회 (발송시간 오름차순 / 삭제되지 않은 메시지만)
    List<Message> findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(Long chatRoomId);

    //해당 채팅방의 마지막 메세지 조회
    Optional<Message> findTopByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtDesc(Long chatRoomId);
}