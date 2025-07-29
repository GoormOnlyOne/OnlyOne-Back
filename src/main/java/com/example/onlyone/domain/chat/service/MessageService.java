package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
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
    public Message saveMessage(Long chatRoomId, Long userId, String text) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() ->
                        new IllegalArgumentException("채팅방을 찾을 수 없습니다. ID: " + chatRoomId));

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("유저를 찾을 수 없습니다. ID: " + userId));

        Message message = new Message();
        message.setChatRoom(chatRoom);
        message.setUser(user);
        message.setText(text);
        message.setSentAt(LocalDateTime.now());  // 자동 설정
        message.setDeleted(false);

        return messageRepository.save(message);
    }

    /**
     * 메시지 논리적 삭제
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() ->
                        new IllegalArgumentException("메시지를 찾을 수 없습니다. ID: " + messageId));

        if (!message.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("본인이 작성한 메시지만 삭제할 수 있습니다.");
        }

        message.setDeleted(true);
    }

    /**
     * 삭제되지 않은 모든 메시지 목록 조회
     */
    public List<Message> getMessages(Long chatRoomId) {
        return messageRepository.findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(chatRoomId);
    }

}
