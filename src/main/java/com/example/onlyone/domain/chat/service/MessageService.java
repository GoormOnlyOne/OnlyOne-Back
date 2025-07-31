package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.exception.MyChatException;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    /**
     * 메시지 저장
     */
    @Transactional
    public ChatMessageResponse saveMessage(Long chatRoomId, Long userId, String text) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new MyChatException("채팅방을 찾을 수 없습니다. ID: " + chatRoomId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MyChatException("사용자를 찾을 수 없습니다. ID: " + userId));

        Message message = Message.builder()
                .chatRoom(chatRoom)
                .user(user)
                .text(text)
                .sentAt(LocalDateTime.now())
                .deleted(false)
                .build();

        return ChatMessageResponse.from(messageRepository.save(message));
    }

    /**
     * 메시지 논리적 삭제
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        int updated = messageRepository.softDeleteByUser(messageId, userId);
        if (updated == 0) {
            throw new MyChatException("삭제 권한이 없거나 메시지가 존재하지 않습니다.");
        }
    }

    /**
     * 삭제되지 않은 모든 메시지 목록 조회
     */
    public List<ChatMessageResponse> getMessages(Long chatRoomId) {
        return messageRepository.findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(chatRoomId).stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }
}
