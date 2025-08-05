package com.example.onlyone.domain.user.repository;

import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
<<<<<<< HEAD
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
=======
>>>>>>> develop

public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByKakaoId(Long kakaoId);
}
