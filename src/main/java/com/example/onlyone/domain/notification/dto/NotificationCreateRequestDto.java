package com.example.onlyone.domain.notification.dto;

import com.example.onlyone.domain.notification.entity.Type;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

/**
 * 알림 생성 요청 DTO
 *
 * 새로운 알림을 생성하기 위한 요청 데이터를 담는 DTO입니다.
 */
@Getter
@Builder
public class NotificationCreateRequestDto {

  @NotNull(message = "사용자 ID는 필수입니다")
  private final Long userId;

  @NotNull(message = "알림 타입은 필수입니다")
  private final Type type;

  private final String[] args;
}
