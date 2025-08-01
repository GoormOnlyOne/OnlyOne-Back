package com.example.onlyone.domain.notification.dto.requestDto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 알림 읽음 처리 요청 DTO
 *
 * 사용자가 선택한 특정 알림들을 읽음 상태로 변경하기 위한 요청 DTO입니다.
 * 개별 알림 또는 다중 알림을 한 번에 처리할 수 있습니다.
 */
@Getter
@Builder
public class NotificationReadRequestDto {

  @NotEmpty
  private final List<Long> notificationIds;

}
