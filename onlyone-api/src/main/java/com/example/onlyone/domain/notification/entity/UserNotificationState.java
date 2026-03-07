package com.example.onlyone.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 유저별 알림 전체 읽음 워터마크.
 * {@code read_all_upto_id} 이하의 notification_id는 모두 읽음으로 간주한다.
 * mark-all 시 대량 UPDATE 대신 이 값만 갱신하여 row lock 경합을 제거한다.
 */
@Entity
@Table(name = "user_notification_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotificationState {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "read_all_upto_id", nullable = false)
    private Long readAllUptoId = 0L;

    @Column(name = "updated_at", nullable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)")
    private LocalDateTime updatedAt;

    public UserNotificationState(Long userId, Long readAllUptoId) {
        this.userId = userId;
        this.readAllUptoId = readAllUptoId;
        this.updatedAt = LocalDateTime.now();
    }
}
