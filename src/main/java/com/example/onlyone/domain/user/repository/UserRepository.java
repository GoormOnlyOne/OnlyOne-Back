package com.example.onlyone.domain.user.repository;

import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User,Long> {
}
