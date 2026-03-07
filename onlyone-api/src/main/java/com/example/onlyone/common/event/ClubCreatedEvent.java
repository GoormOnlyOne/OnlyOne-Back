package com.example.onlyone.common.event;

/**
 * 클럽 생성 이벤트
 * Club 도메인에서 발행하여 Chat, Search 등 다른 도메인이 구독
 */
public record ClubCreatedEvent(Long clubId, Long leaderUserId, String clubName) {
}
