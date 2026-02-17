package com.example.onlyone.domain.interest.repository;

import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterestRepository extends JpaRepository<Interest,Long> {
    Optional<Interest> findByCategory(Category category);
}