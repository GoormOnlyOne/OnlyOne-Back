package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import org.springframework.data.jpa.repository.JpaRepository;

import javax.swing.text.html.Option;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom,Long> {
    // 방ID + 모임ID 로 단건 조회
    Optional<ChatRoom> findByChatRoomIdAndClubClubId(Long chatRoomId, Long clubId);
}
