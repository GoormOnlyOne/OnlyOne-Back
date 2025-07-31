package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

  @Column(name = "type")
  @NotNull
  @Enumerated(EnumType.STRING)
  private Type type;

  @Column(name = "template")
  @NotNull
  private String template;

  public NotificationType(Type type, String template) {
    this.type = type;
    this.template = template;
  }

  // 알림 유형별로 템플릿을 통해서 렌더링하는 기능
  public String render(String... args) {
    return String.format(this.template, (Object[]) args);
  }
}