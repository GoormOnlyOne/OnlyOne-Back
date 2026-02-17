package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchService {
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final UserInterestRepository userInterestRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final ClubElasticsearchRepository clubElasticsearchRepository;

    // 사용자 맞춤 추천
    @Cacheable(value = "recommendations",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #page + '_' + #size")
    @Transactional(readOnly = true)
    public List<ClubResponseDto> recommendedClubs(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        User user = userService.getCurrentUser();

        // 사용자 관심사 조회
        List<Long> interestIds = userInterestRepository.findInterestIdsByUserId(user.getUserId());

        // 관심사가 없는 경우 빈 리스트 반환
        if (interestIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 1단계: 관심사 + 지역 일치 (사용자 지역 정보가 유효한 경우만)
        if (hasValidLocation(user)) {
            List<Object[]> resultList = clubRepository.searchByUserInterestAndLocation(
                    interestIds, user.getCity(), user.getDistrict(), user.getUserId(), pageRequest);

            if (!resultList.isEmpty()) {
                if (size == 5) {
                    resultList = new ArrayList<>(resultList);
                    Collections.shuffle(resultList);
                    resultList = resultList.subList(0, Math.min(5, resultList.size()));
                }
                return convertToClubResponseDto(resultList);
            }
        }

        // 2단계: 관심사 일치
        List<Object[]> resultList = clubRepository.searchByUserInterests(interestIds, user.getUserId(), pageRequest);

        if (size == 5) {
            resultList = new ArrayList<>(resultList);
            Collections.shuffle(resultList);
            resultList = resultList.subList(0, Math.min(5, resultList.size()));
        }

        return convertToClubResponseDto(resultList);
    }

    // 모임 검색 (관심사)
    @Transactional(readOnly = true)
    public List<ClubResponseDto> searchClubByInterest(Long interestId, int page) {
        if (interestId == null) {
            throw new CustomException(ErrorCode.INVALID_INTEREST_ID);
        }

        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByInterest(interestId, pageRequest);
        Long userId = userService.getCurrentUserId();
        List<Long> joinedClubIds = userClubRepository.findByClubIdsByUserId(userId);
        return convertToClubResponseDtoWithJoinStatus(resultList, joinedClubIds);
    }

    // 모임 검색 (지역)
    @Transactional(readOnly = true)
    public List<ClubResponseDto> searchClubByLocation(String city, String district, int page) {
        if (city == null || district == null || city.trim().isEmpty() || district.trim().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_LOCATION);
        }

        PageRequest pageRequest = PageRequest.of(page, 20);
        List<Object[]> resultList = clubRepository.searchByLocation(city, district, pageRequest);
        Long userId = userService.getCurrentUserId();
        List<Long> joinedClubIds = userClubRepository.findByClubIdsByUserId(userId);

        return convertToClubResponseDtoWithJoinStatus(resultList, joinedClubIds);
    }

    // 통합 검색 (키워드 + 필터) - 하이브리드 방식
    // DB 조회와 ES/MySQL 검색을 병렬 실행하여 레이턴시 최소화
    public List<ClubResponseDto> searchClubs(SearchFilterDto filter) {
        // 지역 필터 유효성 검증
        if (!filter.isLocationValid()) {
            throw new CustomException(ErrorCode.INVALID_SEARCH_FILTER);
        }
        // 키워드 유효성 검증
        if (!filter.isKeywordValid()) {
            throw new CustomException(ErrorCode.SEARCH_KEYWORD_TOO_SHORT);
        }

        Long userId = userService.getCurrentUserId();

        // DB 조회를 비동기로 시작 (ES/MySQL 검색과 병렬 실행)
        CompletableFuture<List<Long>> joinedFuture = CompletableFuture.supplyAsync(
                () -> userClubRepository.findByClubIdsByUserId(userId));

        if (filter.hasKeyword()) {
            List<ClubDocument> esResults = searchWithElasticsearch(filter);
            List<Long> joinedClubIds = joinedFuture.join();
            return convertElasticsearchResultsWithJoinStatus(esResults, joinedClubIds);
        } else {
            List<Object[]> resultList = searchWithMysql(filter);
            List<Long> joinedClubIds = joinedFuture.join();
            return convertMysqlResultsWithJoinStatus(resultList, joinedClubIds);
        }
    }

    // 함께하는 멤버들의 다른 모임 조회
    @Cacheable(value = "teammatesClubs",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #page + '_' + #size")
    @Transactional(readOnly = true)
    public List<ClubResponseDto> getClubsByTeammates(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        Long userId = userService.getCurrentUserId();
        List<Object[]> resultList = clubRepository.findClubsByTeammates(userId, pageRequest);

        // 홈 화면에서 보여주는건 상위 20개 중 랜덤으로 최대 5개
        if (size == 5) {
            resultList = new ArrayList<>(resultList); // 가변 리스트로 변환
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
    @Transactional(readOnly = true)
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

    // ES 검색 메서드 — 통합 dynamic query 사용
    private List<ClubDocument> searchWithElasticsearch(SearchFilterDto filter) {
        String keyword = filter.keyword().trim();
        Pageable pageable = createPageable(filter);

        String city = filter.hasLocation() ? filter.city().trim() : null;
        String district = filter.hasLocation() ? filter.district().trim() : null;

        return clubElasticsearchRepository.search(keyword, city, district, filter.interestId(), pageable);
    }

    // MySQL 검색 메서드 (키워드 없는 필터 검색)
    private List<Object[]> searchWithMysql(SearchFilterDto filter) {
        PageRequest pageRequest = PageRequest.of(filter.page(), 20);

        if (filter.hasLocation() && filter.interestId() != null) {
            // 지역 + 관심사
            return clubRepository.searchByUserInterestAndLocation(
                    List.of(filter.interestId()),
                    filter.city().trim(),
                    filter.district().trim(),
                    null, // userId는 null (전체 검색)
                    pageRequest);
        } else if (filter.hasLocation()) {
            // 지역만
            return clubRepository.searchByLocation(filter.city(), filter.district(), pageRequest);
        } else if (filter.interestId() != null) {
            // 관심사만
            return clubRepository.searchByInterest(filter.interestId(), pageRequest);
        } else {
            // 조건 없음 - 빈 결과 반환
            return new ArrayList<>();
        }
    }

    // ES 결과를 ClubResponseDto로 변환 (가입 상태 포함)
    private List<ClubResponseDto> convertElasticsearchResultsWithJoinStatus(List<ClubDocument> results, List<Long> joinedClubIds) {
        return results.stream().map(document -> {
            boolean isJoined = joinedClubIds.contains(document.getClubId());
            return new ClubResponseDto(
                    document.getClubId(),
                    document.getName(),
                    document.getDescription(),
                    document.getInterestKoreanName(),
                    document.getDistrict(),
                    document.getMemberCount(),
                    document.getClubImage(),
                    isJoined
            );
        }).toList();
    }

    // MySQL 필터 결과를 ClubResponseDto로 변환 (가입 상태 포함)
    private List<ClubResponseDto> convertMysqlResultsWithJoinStatus(List<Object[]> results, List<Long> joinedClubIds) {
        return results.stream().map(result -> {
            Club club = (Club) result[0];
            Long memberCount = (Long) result[1];
            boolean isJoined = joinedClubIds.contains(club.getClubId());
            return ClubResponseDto.from(club, memberCount, isJoined);
        }).toList();
    }

    // Pageable 생성 (ES용)
    private Pageable createPageable(SearchFilterDto filter) {
        Sort sort;

        if (filter.sortBy() == SearchFilterDto.SortType.LATEST) {
            sort = Sort.by(Sort.Order.desc("createdAt"));
        } else {
            sort = Sort.by(Sort.Order.desc("memberCount"));
        }

        return PageRequest.of(filter.page(), 20, sort);
    }

    // 사용자의 지역 정보가 유효한지 확인
    private boolean hasValidLocation(User user) {
        return user.getCity() != null && !user.getCity().trim().isEmpty() &&
               user.getDistrict() != null && !user.getDistrict().trim().isEmpty();
    }
}
