package com.example.onlyone.domain.chat.port;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MySQL(JPA) 기반 채팅 메시지 저장소 어댑터.
 * {@code app.chat.storage=mysql} 이거나 미설정 시 기본 활성화.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chat.storage", havingValue = "mysql", matchIfMissing = true)
public class MysqlChatMessageStorageAdapter implements ChatMessageStoragePort {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ChatMessageItemDto save(Long chatRoomId, Long userId, String nickname,
                                   String profileImage, String text, LocalDateTime sentAt) {
        ChatRoom chatRoom = chatRoomRepository.getReferenceById(chatRoomId);
        User user = userRepository.getReferenceById(userId);

        Message saved = messageRepository.save(Message.builder()
                .chatRoom(chatRoom)
                .user(user)
                .text(text)
                .sentAt(sentAt)
                .deleted(false)
                .build());

        return toDto(saved, userId, nickname, profileImage);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatMessageItemDto> findById(Long messageId) {
        return messageRepository.findById(messageId)
                .map(this::toDto);
    }

    @Override
    @Transactional
    public boolean markAsDeleted(Long messageId) {
        return messageRepository.findById(messageId)
                .map(m -> {
                    m.markAsDeleted();
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageItemDto> findLatest(Long chatRoomId, int limit) {
        return messageRepository.findLatest(chatRoomId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageItemDto> findOlderThan(Long chatRoomId, LocalDateTime cursorAt,
                                                  Long cursorId, int limit) {
        return messageRepository.findOlderThan(chatRoomId, cursorAt, cursorId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageItemDto> findLastMessagesByChatRoomIds(List<Long> chatRoomIds) {
        if (chatRoomIds.isEmpty()) {
            return List.of();
        }
        return messageRepository.findLastMessagesByChatRoomIdsNative(chatRoomIds)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ── mapping ──

    private ChatMessageItemDto toDto(Message m) {
        return new ChatMessageItemDto(
                m.getMessageId(),
                m.getChatRoom().getChatRoomId(),
                m.getUser().getUserId(),
                m.getUser().getNickname(),
                m.getUser().getProfileImage(),
                m.getText(),
                m.getSentAt(),
                m.isDeleted()
        );
    }

    private ChatMessageItemDto toDto(Message m, Long userId, String nickname, String profileImage) {
        return new ChatMessageItemDto(
                m.getMessageId(),
                m.getChatRoom().getChatRoomId(),
                userId,
                nickname,
                profileImage,
                m.getText(),
                m.getSentAt(),
                m.isDeleted()
        );
    }

    private ChatMessageItemDto toDto(MessageRepository.LastMessageProjection p) {
        return new ChatMessageItemDto(
                p.getMessageId(),
                p.getChatRoomId(),
                p.getUserId(),
                p.getNickname(),
                p.getProfileImage(),
                p.getText(),
                p.getSentAt(),
                p.getDeleted()
        );
    }
}
