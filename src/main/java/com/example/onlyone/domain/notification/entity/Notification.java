package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 알림 엔티티
 *
 * 사용자에게 전송되는 모든 알림 정보를 저장하는 핵심 엔티티입니다. SSE(Server-Sent Events)와 FCM(Firebase Cloud Messaging)을 통한
 * 실시간 알림 전송을 지원합니다.
 *
 * 주요 기능: - 알림 타입별 템플릿 기반 메시지 생성 - 읽음/읽지않음 상태 관리 - FCM 전송 상태 추적
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "notification_id", updatable = false)
  private Long notificationId;

  /**
   * 실제 사용자에게 표시될 알림 메시지 NotificationType의 템플릿과 파라미터를 조합하여 생성됩니다.
   */
  @Column(name = "content")
  @NotNull
  private String content;

  /**
   * 알림 읽음 상태 false: 읽지 않음 (기본값) true: 읽음 처리됨
   *
   * 읽지 않은 알림 개수 계산과 UI 표시에 사용됩니다.
   */
  @Column(name = "is_read")
  @NotNull
  private Boolean isRead = false;

  /**
   * 알림 타입 정보 알림의 종류(채팅, 정산, 좋아요, 댓글)를 나타내며 각 타입별로 다른 템플릿과 처리 로직을 적용합니다.
   */
  @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
  @JoinColumn(name = "type_id")
  @NotNull
  private NotificationType notificationType;

  /**
   * 알림을 받을 사용자
   */
  @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
  @JoinColumn(name = "user_id", updatable = false)
  @NotNull
  private User user;

  /**
   * FCM 전송 완료 상태 false: FCM 미전송 (기본값) true: FCM 전송 완료
   * <p>
   * 향후 FCM 재전송 로직이나 전송 실패 처리에 활용할 수 있습니다.
   */
  @Column(name = "fcm_sent", nullable = false)
  private Boolean fcmSent = false;

  /**
   * 알림 생성 팩토리 메서드
   *
   * @param user             알림 수신자
   * @param notificationType 알림 타입 (템플릿 포함)
   * @param content          템플릿에 적용할 파라미터들
   * @return 생성된 알림 객체
   */
  public static Notification create(User user, NotificationType notificationType,
      String... content) {
    Notification n = new Notification();
    n.user = user;
    n.notificationType = notificationType;
    n.content = notificationType.render(content); // 템플릿 렌더링
    n.isRead = false; // 기본값: 읽지 않음
    n.fcmSent = false; // 기본값: FCM 미전송
    return n;
  }

  /**
   * 알림을 읽음 상태로 변경
   * <p>
   * 사용자가 알림을 확인했을 때 호출됩니다. 읽음 처리 후 SSE를 통해 실시간으로 읽지 않은 개수가 업데이트됩니다.
   */
  public void markAsRead() {
    this.isRead = true;
  }

  /**
   * FCM 전송 상태 업데이트
   * <p>
   * FCM 전송 성공/실패 여부를 기록합니다. 향후 전송 실패한 알림들을 재전송하는데 사용됩니다.
   *
   * @param sent FCM 전송 성공 여부
   */
  public void markFcmSent(boolean sent) {
    this.fcmSent = sent;
  }
}