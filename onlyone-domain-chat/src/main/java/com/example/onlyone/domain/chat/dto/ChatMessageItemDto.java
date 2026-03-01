package com.example.onlyone.domain.chat.dto;

import java.time.LocalDateTime;

/**
 * Storage 무관 중간 DTO.
 * Port 인터페이스가 반환하는 공통 메시지 데이터 — JPA 엔티티 의존 없음.
 */
public record ChatMessageItemDto(
        Long messageId,
        Long chatRoomId,
        Long senderId,
        String senderNickname,
        String senderProfileImage,
        String text,
        LocalDateTime sentAt,
        boolean deleted
) {}
