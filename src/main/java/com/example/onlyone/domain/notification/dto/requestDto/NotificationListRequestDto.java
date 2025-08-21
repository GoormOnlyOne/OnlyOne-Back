package com.example.onlyone.domain.notification.dto.requestDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Getter;

/**
 * 알림 목록 조회 요청 DTO
 *
 * 알림 목록 조회 시 사용되는 요청 파라미터를 담는 DTO입니다.
 */
@Getter
@Builder
public class NotificationListRequestDto {

  @NotNull(message = "사용자 ID는 필수입니다")
  @Positive(message = "사용자 ID는 양수여야 합니다")
  private final Long userId;

  @JsonProperty("isRead")
  private final Boolean isRead;

  @Positive(message = "마지막 ID는 양수여야 합니다")
  private final Long lastId;

  @Min(value = 1, message = "페이지 크기는 최소 1이어야 합니다")
  @Max(value = 100, message = "페이지 크기는 최대 100이어야 합니다")
  private final Integer size;
}
