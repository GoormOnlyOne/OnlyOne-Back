package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
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
    private final NotificationService notificationService;
    private final UserChatRoomRepository userChatRoomRepository;

    /**
     * 메시지 저장
     */
    @Transactional
    public ChatMessageResponse saveMessage(Long chatRoomId, Long userId, String text) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Message message = Message.builder()
                .chatRoom(chatRoom)
                .user(user)
                .text(text)
                .sentAt(LocalDateTime.now())
                .deleted(false)
                .build();
        messageRepository.save(message);
        // 채팅방 멤버들에게 알림 일괄 생성
        notifyChatRoomMembers(chatRoom, user);
        return ChatMessageResponse.from(message);
    }

    /**
     * 채팅방의 모든 멤버에게 CHAt 알림 생성 (보낸 사람은 제외)
     * TODO: 효율성을 위해 토픽 / bulk / 비동기 방식 등 고려 필요
     */
    private void notifyChatRoomMembers(ChatRoom chatRoom, User sender) {
        List<UserChatRoom> members = userChatRoomRepository.findAllByChatRoomId(chatRoom.getChatRoomId());
        for (UserChatRoom userChatRoom : members) {
            User target = userChatRoom.getUser();
            if (target == null) continue;
            if (target.getUserId().equals(sender.getUserId())) continue;
            notificationService.createNotification(target, Type.CHAT, new String[]{sender.getNickname()});
        }
    }

    /**
     * 메시지 논리적 삭제
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new CustomException(ErrorCode.MESSAGE_NOT_FOUND));

        if (!message.isOwnedBy(userId)) {
            throw new CustomException(ErrorCode.MESSAGE_DELETE_ERROR);
        }

        message.markAsDeleted();
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
