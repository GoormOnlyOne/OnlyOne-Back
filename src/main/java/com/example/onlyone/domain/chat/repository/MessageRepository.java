package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message,Long> {
}

