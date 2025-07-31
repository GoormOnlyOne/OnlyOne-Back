package com.example.onlyone.domain.notification.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 알림 읽음 처리 응답 DTO
 *
 * 알림 읽음 처리 완료 후 클라이언트에게 반환되는 결과 정보를 담는 DTO입니다.
 * 실제 처리된 개수와 원본 요청 정보를 포함합니다.
 */
@Getter
@Builder
public class NotificationReadResponseDto {

  private final Integer updatedCount;

  private final List<Long> notificationIds;
}