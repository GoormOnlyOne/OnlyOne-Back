package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<AppNotification, Long>, NotificationRepositoryCustom {
    // QueryDSL로 모든 쿼리를 이관했으므로 네이티브 쿼리 제거
    // NotificationRepositoryCustom 인터페이스의 메서드들을 구현체에서 제공

}