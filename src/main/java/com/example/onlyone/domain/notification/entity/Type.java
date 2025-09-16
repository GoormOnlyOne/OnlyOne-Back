package com.example.onlyone.domain.notification.entity;

import lombok.Getter;

/**
 * 알림 타입 열거형
 *
 * 시스템에서 지원하는 모든 알림 종류를 정의합니다.
 * 각 타입은 클릭 시 이동할 타겟 타입을 정의합니다.
 */
@Getter
public enum Type {
  CHAT("CHAT"),
  SETTLEMENT("SETTLEMENT"),
  LIKE("POST"),
  COMMENT("POST"),
  REFEED("FEED");

  private final String targetType;

  Type(String targetType) {
    this.targetType = targetType;
  }

}