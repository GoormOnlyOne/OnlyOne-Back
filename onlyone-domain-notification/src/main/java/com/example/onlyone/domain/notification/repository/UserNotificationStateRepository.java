package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.UserNotificationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserNotificationStateRepository extends JpaRepository<UserNotificationState, Long> {

    @Query(value = "SELECT read_all_upto_id FROM user_notification_state WHERE user_id = :userId",
            nativeQuery = true)
    Optional<Long> findReadAllUptoIdByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = "INSERT INTO user_notification_state(user_id, read_all_upto_id, updated_at) " +
            "VALUES (:userId, :maxId, NOW(6)) " +
            "ON DUPLICATE KEY UPDATE " +
            "read_all_upto_id = GREATEST(read_all_upto_id, VALUES(read_all_upto_id)), " +
            "updated_at = NOW(6)",
            nativeQuery = true)
    void upsertReadAllUptoId(@Param("userId") Long userId, @Param("maxId") Long maxId);
}
