package com.example.onlyone.domain.club.repository;

// import com.example.onlyone.domain.search.dto.request.SearchFilterDto;  // TODO: 순환 의존성 방지
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface ClubRepositoryCustom {
    // TODO: 순환 의존성 방지 - SearchService에서 처리
    // List<Object[]> searchByKeywordWithFilter(SearchFilterDto filter, int page, int size);
    List<Object[]> findClubsByTeammates(Long userId, Pageable pageable);
    List<Object[]> searchByUserInterestAndLocation(List<Long> interestIds, String city, String district, Long userId, Pageable pageable);
    List<Object[]> searchByUserInterests(List<Long> interestIds, Long userId, Pageable pageable);
    List<Object[]> searchByInterest(Long interestId, Pageable pageable);
    List<Object[]> searchByLocation(String city, String district, Pageable pageable);
}