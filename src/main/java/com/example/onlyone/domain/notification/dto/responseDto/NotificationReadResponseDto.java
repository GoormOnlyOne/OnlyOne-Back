package com.example.onlyone.domain.notification.dto.responseDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

  @NotNull(message = "업데이트된 개수는 필수입니다")
  @PositiveOrZero(message = "업데이트된 개수는 0 이상이어야 합니다")
  private final Integer updatedCount;

  @NotNull(message = "알림 ID 목록은 필수입니다")
  @Valid
  private final List<@NotNull(message = "알림 ID는 null일 수 없습니다") Long> notificationIds;
}