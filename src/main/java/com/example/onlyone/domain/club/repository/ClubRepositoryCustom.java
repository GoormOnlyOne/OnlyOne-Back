package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface ClubRepositoryCustom {
    List<Object[]> searchByKeywordWithFilter(SearchFilterDto filter, int page, int size);
    List<Object[]> findClubsByTeammates(Long userId, Pageable pageable);
    List<Object[]> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable);
    List<Object[]> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable);
    List<Object[]> searchByInterest(Long interestId, Pageable pageable);
    List<Object[]> searchByLocation(String city, String district, Pageable pageable);
}