package com.example.onlyone.domain.notification.dto.requestDto;

import com.example.onlyone.domain.notification.entity.Type;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 생성 요청 DTO
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationCreateRequestDto {

  @NotNull(message = "사용자 ID는 필수입니다")
  @Positive(message = "사용자 ID는 양수여야 합니다")
  private Long userId;

  @NotNull(message = "알림 타입은 필수입니다")
  private Type type;

  @NotNull(message = "템플릿 파라미터는 null일 수 없습니다")
  @Size(max = 10, message = "템플릿 파라미터는 최대 10개까지 가능합니다")
  private String[] args = new String[0];

  /** 정적 팩토리 메서드 */
  public static NotificationCreateRequestDto of(Long userId, Type type, String... args) {
    return new NotificationCreateRequestDto(userId, type, args);
  }
}
