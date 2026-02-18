package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.common.util.MessageUtils;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageCommandService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ChatPublisher chatPublisher;
    private final ObjectMapper objectMapper;

    private static final int MAX_TEXT_LENGTH = 2000;

    /**
     * REST 경로: 메시지 저장 + Redis Pub/Sub 발행
     */
    @Transactional
    public ChatMessageResponse sendAndPublish(Long chatRoomId, Long userId, String text) {
        ChatMessageResponse response = saveMessage(chatRoomId, userId, text);
        publish(chatRoomId, response);
        return response;
    }

    /**
     * WebSocket 경로: DB 저장 없이 즉시 Redis 발행 (비동기 저장은 AsyncMessageService가 담당)
     */
    public void publishImmediately(Long chatRoomId, Long senderId,
                                   String nickname, String profileImage, String rawText) {
        ChatMessageResponse response = ChatMessageResponse.forWebSocket(
                chatRoomId, senderId, nickname, profileImage, rawText);
        publish(chatRoomId, response);
    }

    /**
     * 메시지 DB 저장 (AsyncMessageService에서도 호출)
     */
    @Transactional
    public ChatMessageResponse saveMessage(Long chatRoomId, Long userId, String text) {
        if (text == null || text.isBlank()) throw new CustomException(ErrorCode.MESSAGE_BAD_REQUEST);

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        boolean joined = userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(
                user.getUserId(), chatRoomId);
        if (!joined) throw new CustomException(ErrorCode.FORBIDDEN_CHAT_ROOM);

        boolean isImage = MessageUtils.isImageMessage(text);
        String imageUrl = null;
        String storedText;

        if (isImage) {
            imageUrl = validateAndExtractImageUrl(text);
            storedText = MessageUtils.IMAGE_PREFIX + imageUrl;
        } else {
            storedText = truncateText(text);
        }

        Message saved = messageRepository.save(Message.builder()
                .chatRoom(chatRoom).user(user).text(storedText)
                .sentAt(LocalDateTime.now()).deleted(false).build());

        return new ChatMessageResponse(
                saved.getMessageId(), chatRoomId,
                user.getUserId(), user.getNickname(), user.getProfileImage(),
                isImage ? null : storedText, imageUrl,
                saved.getSentAt(), false);
    }

    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new CustomException(ErrorCode.MESSAGE_NOT_FOUND));
        if (m.isDeleted()) throw new CustomException(ErrorCode.MESSAGE_CONFLICT);
        if (!m.isOwnedBy(userId)) throw new CustomException(ErrorCode.MESSAGE_DELETE_ERROR);
        m.markAsDeleted();
    }

    // ── private ──

    private void publish(Long chatRoomId, ChatMessageResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            chatPublisher.publish(chatRoomId, payload);
        } catch (JsonProcessingException e) {
            log.error("[MessageCommand] JSON serialization failed: chatRoomId={}", chatRoomId, e);
            throw new CustomException(ErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    private String validateAndExtractImageUrl(String text) {
        String url = text.substring(MessageUtils.IMAGE_PREFIX.length()).trim();
        if (url.isBlank() || url.contains(",") || url.contains(" ")) {
            throw new CustomException(ErrorCode.MESSAGE_BAD_REQUEST);
        }
        if (!url.matches("(?i).+\\.(png|jpg|jpeg)$")) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }
        return url;
    }

    private String truncateText(String text) {
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }
}
