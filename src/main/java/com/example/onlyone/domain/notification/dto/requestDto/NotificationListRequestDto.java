package com.example.onlyone.domain.notification.dto.requestDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
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

  private final Long userId;

  @JsonProperty("isRead")
  private final Boolean isRead;

  private final Long lastId;

  @Min(1)
  private final Integer size;
}
