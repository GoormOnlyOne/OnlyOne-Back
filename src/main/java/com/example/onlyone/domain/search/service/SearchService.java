package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SearchService {
    private final ClubRepository clubRepository;
    private final UserService userService;
    private final UserInterestRepository userInterestRepository;

    // 사용자 맞춤 추천
    public List<ClubResponseDto> recommendedClubs(int page) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        User user = userService.getCurrentUser();

        // 사용자 관심사 조회
        List<Long> interestIds = userInterestRepository.findInterestIdsByUserId(user.getUserId());

        // 1단계: 관심사 + 지역 일치
        List<Object[]> resultList = clubRepository.searchByUserInterestAndLocation(interestIds, user.getCity(), user.getDistrict(), pageRequest);

        if(resultList.size() > 0) {
            return convertToClubResponseDto(resultList);
        }

        // 2단계: 관심사 일치
        resultList = clubRepository.searchByUserInterests(interestIds, pageRequest);
        return convertToClubResponseDto(resultList);
    }

    // 모임 검색 (관심사)
    public List<ClubResponseDto> searchClubByInterest(Long interestId, int page) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByInterest(interestId, pageRequest);

        return convertToClubResponseDto(resultList);
    }

    // 모임 검색 (지역)
    public List<ClubResponseDto> searchClubByLocation(String city, String district, int page) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByLocation(city, district, pageRequest);

        return convertToClubResponseDto(resultList);
    }

    // 통합 검색 (키워드 + 필터)
    public List<ClubResponseDto> searchClubs(SearchFilterDto filter) {
        // 지역 필터 유효성 검증
        if (!filter.isLocationValid()) {
            throw new CustomException(ErrorCode.INVALID_SEARCH_FILTER);
        }
        // 키워드 유효성 검증
        if (!filter.isKeywordValid()) {
            throw new CustomException(ErrorCode.SEARCH_KEYWORD_TOO_SHORT);
        }
        PageRequest pageRequest = PageRequest.of(filter.getPage(), 20);
        
        // 지역은 세트로만 처리
        String city = null;
        String district = null;
        if (filter.hasLocation()) {
            city = filter.getCity().trim();
            district = filter.getDistrict().trim();
        }
        
        List<Object[]> resultList = clubRepository.searchByKeywordWithFilter(
            filter.getKeyword(),
            city,
            district,
            filter.getInterestId(),
            filter.getSortBy().name(),
            pageRequest
        );

        return convertKeywordSearchResults(resultList);
    }

    // 함께하는 멤버들의 다른 모임 조회
    public List<ClubResponseDto> getClubsByTeammates(int page) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        User currentUser = userService.getCurrentUser();
        List<Object[]> resultList = clubRepository.findClubsByTeammates(currentUser.getUserId(), pageRequest);

        return convertToClubResponseDto(resultList);
    }

    // 엔티티 -> DTO 형태로 변환
    private List<ClubResponseDto> convertToClubResponseDto(List<Object[]> results) {
        return results.stream().map(result -> {
            Club club = (Club) result[0];
            Long memberCount = (Long) result[1];
            return ClubResponseDto.from(club, memberCount);
        }).toList();
    }

    // 키워드 검색 결과 전용 변환
    private List<ClubResponseDto> convertKeywordSearchResults(List<Object[]> results) {
        return results.stream().map(result -> {
            return ClubResponseDto.builder()
                    .clubId(((Number) result[0]).longValue())
                    .name((String) result[1])
                    .description((String) result[2])
                    .district((String) result[3])
                    .image((String) result[4])
                    .interest((String) result[5])
                    .memberCount(((Number) result[6]).longValue())
                    .build();
        }).toList();
    }
}
