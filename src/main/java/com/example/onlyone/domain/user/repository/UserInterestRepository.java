package com.example.onlyone.domain.user.repository;

import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

    // 사용자 관심사 ID 목록 조회
    @Query("SELECT ui.interest.interestId FROM UserInterest ui WHERE ui.user.userId = :userId")
    List<Long> findInterestIdsByUserId(@Param("userId") Long userId);

    // 사용자 관심사 카테고리 목록 조회
    @Query("SELECT ui.interest.category FROM UserInterest ui WHERE ui.user.userId = :userId")
    List<Category> findCategoriesByUserId(@Param("userId") Long userId);
}
