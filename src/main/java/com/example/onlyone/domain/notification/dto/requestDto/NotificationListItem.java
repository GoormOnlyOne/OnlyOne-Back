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
        .targetType(this.type.getTargetType())
        .targetId(extractTargetId())
        .build();
  }
  
  /**
   * 알림 타입별로 targetId 추출
   * 
   * 현재는 content에서 ID를 직접 추출할 수 없으므로,
   * 향후 다음 방법들을 고려할 수 있습니다:
   * 1. 알림 템플릿에 ID 정보 포함
   * 2. 알림 생성 시 별도 컨텍스트 저장
   * 3. 클라이언트에서 알림 클릭 시 추가 API 호출
   */
  private Long extractTargetId() {
    // TODO: 실제 구현 시 다음과 같은 방식들을 고려:
    // - 알림 생성 시점의 엔티티 ID를 별도 필드로 저장
    // - content 템플릿을 수정하여 ID 정보 포함
    // - 알림 클릭 처리용 별도 API 엔드포인트 구현
    
    return null; // 현재는 미구현
  }

  public boolean isRead() {
    return this.isRead;
  }

  public boolean isType(Type type) {
    return this.type == type;
  }
}