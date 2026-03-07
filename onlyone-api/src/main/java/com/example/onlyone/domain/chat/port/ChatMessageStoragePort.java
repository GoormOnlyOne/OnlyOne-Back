package com.example.onlyone.domain.chat.port;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 채팅 메시지 저장소 추상화 포트.
 * MySQL / MongoDB 어댑터가 구현한다.
 */
public interface ChatMessageStoragePort {

    ChatMessageItemDto save(Long chatRoomId, Long userId, String nickname,
                            String profileImage, String text, LocalDateTime sentAt);

    Optional<ChatMessageItemDto> findById(Long messageId);

    boolean markAsDeleted(Long messageId);

    List<ChatMessageItemDto> findLatest(Long chatRoomId, int limit);

    List<ChatMessageItemDto> findOlderThan(Long chatRoomId, LocalDateTime cursorAt,
                                           Long cursorId, int limit);

    List<ChatMessageItemDto> findLastMessagesByChatRoomIds(List<Long> chatRoomIds);
}
