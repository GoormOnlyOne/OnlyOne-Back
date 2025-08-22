package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.search.dto.response.MyMeetingListResponseDto;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
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

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SearchService {
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final UserInterestRepository userInterestRepository;
    private final UserSettlementRepository userSettlementRepository;

    // 사용자 맞춤 추천
    public List<ClubResponseDto> recommendedClubs(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        User user = userService.getCurrentUser();

        // 사용자 관심사 조회
        List<Long> interestIds = userInterestRepository.findInterestIdsByUserId(user.getUserId());

        // 1단계: 관심사 + 지역 일치
        List<Object[]> resultList = clubRepository.searchByUserInterestAndLocation(interestIds, user.getCity(), user.getDistrict(), user.getUserId(), pageRequest);

        if (!resultList.isEmpty()) {
            if (size == 5) {
                Collections.shuffle(resultList);
                resultList = resultList.subList(0, Math.min(5, resultList.size()));
            }
            return convertToClubResponseDto(resultList);
        }

        // 2단계: 관심사 일치
        resultList = clubRepository.searchByUserInterests(interestIds, user.getUserId(), pageRequest);

        if (size == 5) {
            Collections.shuffle(resultList);
            resultList = resultList.subList(0, Math.min(5, resultList.size()));
        }

        return convertToClubResponseDto(resultList);
    }

    // 모임 검색 (관심사)
    public List<ClubResponseDto> searchClubByInterest(Long interestId, int page) {
        if (interestId == null) {
            throw new CustomException(ErrorCode.INVALID_INTEREST_ID);
        }

        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByInterest(interestId, pageRequest);
        User user = userService.getCurrentUser();
        List<Long> joinedClubIds = userClubRepository.findByUserUserId(user.getUserId())
                .stream().map(uc -> uc.getClub().getClubId()).toList();

        return convertToClubResponseDtoWithJoinStatus(resultList, joinedClubIds);
    }

    // 모임 검색 (지역)
    public List<ClubResponseDto> searchClubByLocation(String city, String district, int page) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByLocation(city, district, pageRequest);
        User user = userService.getCurrentUser();
        List<Long> joinedClubIds = userClubRepository.findByUserUserId(user.getUserId())
                .stream().map(uc -> uc.getClub().getClubId()).toList();

        return convertToClubResponseDtoWithJoinStatus(resultList, joinedClubIds);
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
        
        // 키워드가 없으면 null로 전달
        String keyword = filter.hasKeyword() ? filter.getKeyword().trim() : null;
        
        List<Object[]> resultList = clubRepository.searchByKeywordWithFilter(
            keyword,
            city,
            district,
            filter.getInterestId(),
            filter.getSortBy().name(),
            pageRequest
        );

        User user = userService.getCurrentUser();
        List<Long> joinedClubIds = userClubRepository.findByUserUserId(user.getUserId())
                .stream().map(uc -> uc.getClub().getClubId()).toList();

        return convertKeywordSearchResultsWithJoinStatus(resultList, joinedClubIds);
    }

    // 함께하는 멤버들의 다른 모임 조회
    public List<ClubResponseDto> getClubsByTeammates(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        User currentUser = userService.getCurrentUser();
        List<Object[]> resultList = clubRepository.findClubsByTeammates(currentUser.getUserId(), pageRequest);

        // 홈 화면에서 보여주는건 상위 20개 중 랜덤으로 최대 5개
        if (size == 5) {
            Collections.shuffle(resultList);
            resultList = resultList.subList(0, Math.min(5, resultList.size()));
            return convertToClubResponseDto(resultList);
        }

        return convertToClubResponseDto(resultList);
    }

    // 엔티티 -> DTO 형태로 변환
    private List<ClubResponseDto> convertToClubResponseDto(List<Object[]> results) {
        return results.stream().map(result -> {
            Club club = (Club) result[0];
            Long memberCount = (Long) result[1];
            return ClubResponseDto.from(club, memberCount, false);
        }).toList();
    }

    // 키워드 검색 결과 가입 상태와 함께 변환
    private List<ClubResponseDto> convertKeywordSearchResultsWithJoinStatus(List<Object[]> results, List<Long> joinedClubIds) {
        return results.stream().map(result -> {
            Long clubId = ((Number) result[0]).longValue();
            String categoryName = (String) result[5];
            String koreanCategoryName = Category.valueOf(categoryName).getKoreanName();
            boolean isJoined = joinedClubIds.contains(clubId);
            
            return ClubResponseDto.builder()
                    .clubId(clubId)
                    .name((String) result[1])
                    .description((String) result[2])
                    .district((String) result[3])
                    .image((String) result[4])
                    .interest(koreanCategoryName)
                    .memberCount(((Number) result[6]).longValue())
                    .isJoined(isJoined)
                    .build();
        }).toList();
    }

    // 엔티티 -> DTO 가입 상태와 함께 변환
    private List<ClubResponseDto> convertToClubResponseDtoWithJoinStatus(List<Object[]> results, List<Long> joinedClubIds) {
        return results.stream().map(result -> {
            Club club = (Club) result[0];
            Long memberCount = (Long) result[1];
            boolean isJoined = joinedClubIds.contains(club.getClubId());
            return ClubResponseDto.from(club, memberCount, isJoined);
        }).toList();
    }

    // 내 모임 목록 조회
    public MyMeetingListResponseDto getMyClubs() {
        User user = userService.getCurrentUser();
        List<Object[]> rows = userClubRepository.findMyClubsWithMemberCount(user.getUserId());
        List<ClubResponseDto> clubResponseDtoList = rows.stream().map(row -> {
            Club club = (Club) row[0];
            Long memberCount = (Long) row[1];
            return ClubResponseDto.from(club, memberCount, true);
        }).toList();
        boolean isUnsettledScheduleExist =
                userSettlementRepository.existsByUserAndSettlementStatusNot(user, SettlementStatus.COMPLETED);
        return new MyMeetingListResponseDto(isUnsettledScheduleExist, clubResponseDtoList);
    }
}
