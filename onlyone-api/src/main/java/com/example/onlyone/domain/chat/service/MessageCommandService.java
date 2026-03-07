package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.port.ChatMessageStoragePort;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.chat.util.MessageUtils;
import com.example.onlyone.domain.chat.exception.ChatErrorCode;
import com.example.onlyone.global.exception.CustomException;
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

    private final ChatMessageStoragePort chatMessageStoragePort;
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
        if (text == null || text.isBlank()) throw new CustomException(ChatErrorCode.MESSAGE_BAD_REQUEST);

        // existsBy 별도 조회 제거 → 단일 쿼리로 user + 채팅방 참여 동시 검증
        UserChatRoomRepository.UserInfoProjection userInfo = userChatRoomRepository
                .findUserInfoIfMember(userId, chatRoomId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.FORBIDDEN_CHAT_ROOM));
        String nickname = userInfo.getNickname();
        String profileImage = userInfo.getProfileImage();

        String storedText = resolveStoredText(text);

        ChatMessageItemDto item = chatMessageStoragePort.save(
                chatRoomId, userId, nickname, profileImage,
                storedText, LocalDateTime.now());

        return ChatMessageResponse.from(item);
    }

    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessageItemDto item = chatMessageStoragePort.findById(messageId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.MESSAGE_NOT_FOUND));
        if (item.deleted()) throw new CustomException(ChatErrorCode.MESSAGE_CONFLICT);
        if (!item.senderId().equals(userId)) throw new CustomException(ChatErrorCode.MESSAGE_FORBIDDEN);
        chatMessageStoragePort.markAsDeleted(messageId);
    }

    // ── private ──

    private void publish(Long chatRoomId, ChatMessageResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            chatPublisher.publish(chatRoomId, payload);
        } catch (JsonProcessingException e) {
            log.error("메시지 JSON 직렬화 실패: chatRoomId={}", chatRoomId, e);
            throw new CustomException(ChatErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    private String resolveStoredText(String text) {
        if (!MessageUtils.isImageMessage(text)) {
            return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
        }
        String url = MessageUtils.extractImageUrl(text);
        if (!MessageUtils.isValidImageUrlFormat(url)) {
            throw new CustomException(ChatErrorCode.MESSAGE_BAD_REQUEST);
        }
        if (!MessageUtils.hasValidImageExtension(url)) {
            throw new CustomException(ChatErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }
        return MessageUtils.IMAGE_PREFIX + url;
    }
}
