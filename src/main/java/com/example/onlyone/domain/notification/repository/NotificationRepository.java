package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 사용자 별로 알림을 최신순으로 정렬해서 조회
    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    // 사용자 별로 읽지 않은 알림들만 조회
    List<Notification> findByUserAndIsReadFalse(User user);
}
