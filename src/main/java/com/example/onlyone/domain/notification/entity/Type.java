package com.example.onlyone.domain.notification.entity;

/**
 * 알림 타입 열거형
 *
 * 시스템에서 지원하는 모든 알림 종류를 정의합니다.
 * 각 타입은 클릭 시 이동할 타겟 타입을 정의합니다.
 */
public enum Type {
  CHAT("CHAT"),           // 채팅방으로 이동
  SETTLEMENT("SETTLEMENT"), // 정산 페이지로 이동
  LIKE("POST"),          // 좋아요 받은 게시글로 이동
  COMMENT("POST"),       // 댓글 달린 게시글로 이동
  REFEED("FEED");        // 리피드된 피드로 이동

  private final String targetType;

  Type(String targetType) {
    this.targetType = targetType;
  }

  public String getTargetType() {
    return targetType;
  }
}