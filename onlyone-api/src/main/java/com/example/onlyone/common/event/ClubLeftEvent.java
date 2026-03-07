package com.example.onlyone.common.event;

/**
 * 모임 탈퇴 이벤트
 * Club 도메인에서 발행하여 Chat 도메인이 구독 (UserChatRoom 정리)
 */
public record ClubLeftEvent(Long clubId, Long userId) {
}
