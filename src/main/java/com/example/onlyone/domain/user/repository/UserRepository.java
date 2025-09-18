package com.example.onlyone.domain.user.repository;

import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByKakaoId(Long kakaoId);
    Optional<User> findByUserId(Long userId);
}