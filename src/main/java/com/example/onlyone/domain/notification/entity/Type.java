package com.example.onlyone.domain.notification.entity;

/**
 * 알림 타입 열거형
 *
 * 시스템에서 지원하는 모든 알림 종류를 정의합니다.
 */
public enum Type {
  CHAT,
  SETTLEMENT,
  LIKE,
  COMMENT,
  REFEED;
}