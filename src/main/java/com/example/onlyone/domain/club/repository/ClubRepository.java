package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.wallet.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClubRepository extends JpaRepository<Club,Long> {
}