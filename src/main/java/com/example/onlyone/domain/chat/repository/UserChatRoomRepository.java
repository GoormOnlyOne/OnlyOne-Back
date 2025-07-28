package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserChatRoomRepository extends JpaRepository<UserChatRoom,Long> {
}
