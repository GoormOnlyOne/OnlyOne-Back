package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageQueryService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    public ChatRoomMessageResponse getChatRoomMessages(
            Long chatRoomId, Integer size, Long cursorId, LocalDateTime cursorAt) {

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        String chatRoomName = chatRoom.resolveName();

        int pageSize = clampPageSize(size);
        int fetchSize = pageSize + 1;
        Pageable limit = PageRequest.of(0, fetchSize);

        List<Message> slice;
        if (cursorId == null || cursorAt == null) {
            slice = messageRepository.findLatest(chatRoomId, limit);
        } else {
            slice = messageRepository.findOlderThan(chatRoomId, cursorAt, cursorId, limit);
        }

        boolean hasMore = slice.size() > pageSize;
        if (hasMore) {
            slice = slice.subList(0, pageSize);
        }

        Collections.reverse(slice);

        Long nextCursorId = null;
        LocalDateTime nextCursorAt = null;
        if (!slice.isEmpty()) {
            Message oldest = slice.get(0);
            nextCursorId = oldest.getMessageId();
            nextCursorAt = oldest.getSentAt();
        }

        List<ChatMessageResponse> messages = slice.stream()
                .map(ChatMessageResponse::from)
                .toList();

        return new ChatRoomMessageResponse(
                chatRoomId, chatRoomName, messages, hasMore, nextCursorId, nextCursorAt);
    }

    private int clampPageSize(Integer size) {
        return (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }
}
