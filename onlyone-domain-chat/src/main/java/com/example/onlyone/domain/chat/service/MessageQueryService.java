package com.example.onlyone.domain.chat.service;

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

        int pageSize = clampPageSize(size);
        List<Message> slice = fetchSlice(chatRoomId, pageSize, cursorId, cursorAt);
        boolean hasMore = slice.size() > pageSize;
        if (hasMore) slice = slice.subList(0, pageSize);
        Collections.reverse(slice);

        return ChatRoomMessageResponse.of(chatRoomId, chatRoom.resolveName(), slice, hasMore);
    }

    private List<Message> fetchSlice(Long chatRoomId, int pageSize, Long cursorId, LocalDateTime cursorAt) {
        Pageable limit = PageRequest.of(0, pageSize + 1);
        if (cursorId == null || cursorAt == null) {
            return messageRepository.findLatest(chatRoomId, limit);
        }
        return messageRepository.findOlderThan(chatRoomId, cursorAt, cursorId, limit);
    }

    private int clampPageSize(Integer size) {
        return (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }
}
