package com.example.onlyone.domain.notification.dto.requestDto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
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

  @NotNull(message = "알림 ID 목록은 필수입니다")
  @Valid
  private final List<@NotNull(message = "알림 ID는 null일 수 없습니다") Long> notificationIds;

}
