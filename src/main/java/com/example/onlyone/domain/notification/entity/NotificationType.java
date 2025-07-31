package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 알림 타입 엔티티
 *
 * 각 알림 타입별로 고유한 메시지 템플릿을 가지며,
 * 동적 파라미터를 통해 개인화된 알림 메시지를 생성합니다.
 *
 * 주요 기능:
 * - 알림 타입별 템플릿 관리 (채팅, 정산, 좋아요, 댓글)
 * - String.format을 활용한 동적 메시지 생성
 * - 알림 타입별 고유한 처리 로직 구분
 *
 * 예시 템플릿:
 * - CHAT: "%s님이 메시지를 보냈습니다."
 * - LIKE: "%s님이 회원님의 게시글을 좋아합니다."
 * - COMMENT: "%s님이 댓글을 남겼습니다: %s"
 */
@Entity
@Table(name = "notification_type")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationType extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "type_id", updatable = false)
  private Long typeId;

  /**
   * 알림 타입 열거형
   * 시스템에서 지원하는 알림 종류를 나타냅니다.
   *
   * 각 타입별 용도:
   * - CHAT: 채팅 메시지 알림
   * - SETTLEMENT: 정산 관련 알림
   * - LIKE: 좋아요 알림
   * - COMMENT: 댓글 알림
   */
  @Column(name = "type")
  @NotNull
  @Enumerated(EnumType.STRING)
  private Type type;

  /**
   * 알림 메시지 템플릿
   * String.format 형식의 템플릿으로, %s, %d 등의 플레이스홀더를 포함합니다.
   *
   * 템플릿 예시:
   * - "%s님이 새로운 메시지를 보냈습니다."
   * - "정산이 완료되었습니다. 금액: %,d원"
   * - "%s님 외 %d명이 좋아요를 눌렀습니다."
   */
  @Column(name = "template")
  @NotNull
  private String template;

  /**
   * 생성자
   *
   * @param type 알림 타입
   * @param template 메시지 템플릿
   */
  public NotificationType(Type type, String template) {
    this.type = type;
    this.template = template;
  }

  /**
   * 알림 메시지 렌더링
   *
   * 템플릿과 동적 파라미터를 조합하여 최종 사용자에게 표시될 메시지를 생성합니다.
   * String.format을 사용하여 타입 안전성을 보장하고 다양한 데이터 타입을 지원합니다.
   *
   * 사용 예시:
   * - template: "%s님이 메시지를 보냈습니다."
   * - args: ["홍길동"]
   * - 결과: "홍길동님이 메시지를 보냈습니다."
   *
   * @param args 템플릿에 적용할 파라미터들 (가변 인자)
   * @return 렌더링된 최종 메시지
   *
   * 주의사항:
   * - 파라미터 개수와 템플릿의 플레이스홀더 개수가 일치해야 함
   * - 파라미터 타입이 템플릿 형식과 호환되어야 함
   * - null 파라미터 처리에 주의 필요
   */
  public String render(String... args) {
    return String.format(this.template, (Object[]) args);
  }
}