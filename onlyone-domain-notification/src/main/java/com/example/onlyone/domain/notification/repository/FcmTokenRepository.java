package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    List<FcmToken> findByUserId(Long userId);

    Optional<FcmToken> findByToken(String token);

    void deleteByToken(String token);

    boolean existsByUserId(Long userId);
}
