package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTypeRepository extends JpaRepository<NotificationType, Long> {

  /**
   * 알림 타입 리포지토리
   *
   * 알림 타입으로 NotificationType 조회
   *
   * 알림 생성 시 타입에 해당하는 템플릿 정보를 조회하는 핵심 메서드입니다.
   * 각 알림 타입별로 고유한 템플릿이 존재하므로 반드시 존재해야 하지만,
   * 데이터 정합성을 위해 Optional로 반환합니다.
   *
   * 사용 시나리오:
   * 1. 채팅 메시지 알림 생성 시 CHAT 타입 조회
   * 2. 좋아요 알림 생성 시 LIKE 타입 조회
   * 3. 정산 알림 생성 시 SETTLEMENT 타입 조회
   * 4. 댓글 알림 생성 시 COMMENT 타입 조회
   *
   * @param type 조회할 알림 타입 (CHAT, SETTLEMENT, LIKE, COMMENT)
   * @return 해당 타입의 NotificationType 정보 (Optional)
   */
  Optional<NotificationType> findByType(Type type);
}
