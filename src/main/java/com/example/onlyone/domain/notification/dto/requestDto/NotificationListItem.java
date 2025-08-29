package com.example.onlyone.domain.notification.dto.requestDto;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 목록 조회용 프로젝션 DTO
 * JPA 프로젝션을 통해 데이터베이스에서 필요한 필드만 조회할 때 사용되는 DTO입니다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationListItem {

  private Long notificationId;

  private String content;

  private Type type;

  @JsonProperty("isRead")
  private Boolean isRead;

  private LocalDateTime createdAt;

  public NotificationItemDto toDto() {
    return NotificationItemDto.builder()
        .notificationId(this.notificationId)
        .content(this.content)
        .type(this.type)
        .isRead(this.isRead)
        .createdAt(this.createdAt)
        .build();
  }

  public boolean isRead() {
    return this.isRead;
  }

  public boolean isType(Type type) {
    return this.type == type;
  }
}