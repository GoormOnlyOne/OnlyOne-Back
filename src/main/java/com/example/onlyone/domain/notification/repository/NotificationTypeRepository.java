package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTypeRepository extends JpaRepository<NotificationType, Long> {

    // 알림 타입으로 조회(댓글이니?, 정산이니? 등등)
    Optional<NotificationType> findByType(Type type);
}
