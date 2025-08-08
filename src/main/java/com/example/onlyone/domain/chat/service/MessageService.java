package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
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

    /**
     * 메시지 저장
     */
    @Transactional
    public ChatMessageResponse saveMessage(Long chatRoomId, Long userId, String text) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        User user = userRepository.findByKakaoId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // IMAGE:: 접두어 여부 확인
        boolean isImageMessage = text != null && text.startsWith("IMAGE::");

        // DB에는 IMAGE:: 제거하고 저장
        String parsedText = isImageMessage ? text.substring("IMAGE::".length()).trim() : text;

        Message message = Message.builder()
                .chatRoom(chatRoom)
                .user(user)
                .text(parsedText) // IMAGE:: 제거된 text 저장
                .sentAt(LocalDateTime.now())
                .deleted(false)
                .build();

        Message saved = messageRepository.save(message);

        // 응답 구성
        return ChatMessageResponse.builder()
                .messageId(saved.getMessageId())
                .chatRoomId(chatRoomId)
                .senderId(user.getKakaoId())
                .senderNickname(user.getNickname())
                .profileImage(user.getProfileImage())
                .text(isImageMessage ? null : parsedText)       // 일반 메시지인 경우 text에
                .imageUrl(isImageMessage ? parsedText : null)   // 이미지 메시지인 경우 imageUrl에
                .sentAt(saved.getSentAt())
                .deleted(false)
                .build();
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

    public List<ChatMessageResponse> getMessages(Long chatRoomId) {
        return messageRepository.findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(chatRoomId).stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }
*/

    @Transactional(readOnly = true)
    public ChatRoomMessageResponse getChatRoomMessages(Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // 채팅방 이름 결정
        String chatRoomName;
        if (chatRoom.getType() == Type.SCHEDULE && chatRoom.getSchedule() != null) {
            chatRoomName = chatRoom.getSchedule().getName();
        } else {
            chatRoomName = chatRoom.getClub().getName(); // 또는 기본값
        }

        List<ChatMessageResponse> messages = messageRepository
                .findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(chatRoomId)
                .stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());

        return ChatRoomMessageResponse.builder()
                .chatRoomId(chatRoomId)
                .chatRoomName(chatRoomName)
                .messages(messages)
                .build();
    }

}
