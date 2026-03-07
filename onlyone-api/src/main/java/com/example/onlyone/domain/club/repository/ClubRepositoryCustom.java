package com.example.onlyone.domain.club.repository;

import org.springframework.data.domain.Pageable;
import java.util.List;

public interface ClubRepositoryCustom {
    List<ClubWithMemberCount> findClubsByTeammates(Long userId, Pageable pageable);
    List<ClubWithMemberCount> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable);
    List<ClubWithMemberCount> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable);
    List<ClubWithMemberCount> searchByInterest(Long interestId, Pageable pageable);
    List<ClubWithMemberCount> searchByLocation(String city, String district, Pageable pageable);
}
