package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.ClubWithMemberCount;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.search.dto.response.MyMeetingListResponseDto;
import com.example.onlyone.domain.search.port.ClubSearchResult;
import com.example.onlyone.domain.search.port.SearchPort;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.domain.search.exception.SearchErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class SearchService {
    private static final int HOME_SAMPLE_SIZE = 5;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final UserInterestRepository userInterestRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final SearchPort searchPort;
    private final Executor searchAsyncExecutor;

    public SearchService(ClubRepository clubRepository,
                         UserClubRepository userClubRepository,
                         UserService userService,
                         UserInterestRepository userInterestRepository,
                         UserSettlementRepository userSettlementRepository,
                         SearchPort searchPort,
                         @Qualifier("customAsyncExecutor") Executor searchAsyncExecutor) {
        this.clubRepository = clubRepository;
        this.userClubRepository = userClubRepository;
        this.userService = userService;
        this.userInterestRepository = userInterestRepository;
        this.userSettlementRepository = userSettlementRepository;
        this.searchPort = searchPort;
        this.searchAsyncExecutor = searchAsyncExecutor;
    }

    // 사용자 맞춤 추천
    // size: 홈 화면 노출용 랜덤 샘플 수 (DB 조회 크기 아님)
    @Transactional(readOnly = true)
    @Cacheable(value = "recommendations",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #page + '_' + #sampleSize")
    public List<ClubResponseDto> recommendedClubs(int page, int sampleSize) {
        PageRequest pageRequest = PageRequest.of(page, DEFAULT_PAGE_SIZE);
        User user = userService.getCurrentUser();

        // 사용자 관심사 조회
        List<Long> interestIds = userInterestRepository.findInterestIdsByUserId(user.getUserId());

        // 관심사가 없는 경우 빈 리스트 반환
        if (interestIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 1단계: 관심사 + 지역 일치 (사용자 지역 정보가 유효한 경우만)
        if (hasValidLocation(user)) {
            List<ClubWithMemberCount> resultList = clubRepository.searchByUserInterestAndLocation(
                    interestIds, user.getCity(), user.getDistrict(), user.getUserId(), pageRequest);

            if (!resultList.isEmpty()) {
                return convertToClubResponseDto(sampleForHome(resultList, sampleSize));
            }
        }

        // 2단계: 관심사 일치
        List<ClubWithMemberCount> resultList = clubRepository.searchByUserInterests(interestIds, user.getUserId(), pageRequest);

        return convertToClubResponseDto(sampleForHome(resultList, sampleSize));
    }

    // 모임 검색 (관심사)
    @Transactional(readOnly = true)
    public List<ClubResponseDto> searchClubByInterest(Long interestId, int page) {
        if (interestId == null) {
            throw new CustomException(SearchErrorCode.INVALID_INTEREST_ID);
        }

        PageRequest pageRequest = PageRequest.of(page, DEFAULT_PAGE_SIZE);
        List<ClubWithMemberCount> resultList = clubRepository.searchByInterest(interestId, pageRequest);
        Long userId = userService.getCurrentUserId();
        List<Long> joinedClubIds = userClubRepository.findByClubIdsByUserId(userId);
        return convertToClubResponseDto(resultList, joinedClubIds);
    }

    // 모임 검색 (지역)
    @Transactional(readOnly = true)
    public List<ClubResponseDto> searchClubByLocation(String city, String district, int page) {
        if (city == null || district == null || city.trim().isEmpty() || district.trim().isEmpty()) {
            throw new CustomException(SearchErrorCode.INVALID_LOCATION);
        }

        PageRequest pageRequest = PageRequest.of(page, DEFAULT_PAGE_SIZE);
        List<ClubWithMemberCount> resultList = clubRepository.searchByLocation(city, district, pageRequest);
        Long userId = userService.getCurrentUserId();
        List<Long> joinedClubIds = userClubRepository.findByClubIdsByUserId(userId);

        return convertToClubResponseDto(resultList, joinedClubIds);
    }

    // 통합 검색 (키워드 + 필터) - 하이브리드 방식
    // DB 조회와 ES/MySQL 검색을 병렬 실행하여 레이턴시 최소화
    @Transactional(readOnly = true)
    public List<ClubResponseDto> searchClubs(SearchFilterDto filter) {
        log.debug("모임 검색 요청: keyword={}, interestId={}, city={}, district={}",
                filter.keyword(), filter.interestId(), filter.city(), filter.district());
        // 지역 필터 유효성 검증
        if (!filter.isLocationValid()) {
            throw new CustomException(SearchErrorCode.INVALID_SEARCH_FILTER);
        }
        // 키워드 유효성 검증
        if (!filter.isKeywordValid()) {
            throw new CustomException(SearchErrorCode.SEARCH_KEYWORD_TOO_SHORT);
        }

        Long userId = userService.getCurrentUserId();

        // DB 조회를 비동기로 시작 (ES/MySQL 검색과 병렬 실행)
        CompletableFuture<List<Long>> joinedFuture = CompletableFuture.supplyAsync(
                () -> userClubRepository.findByClubIdsByUserId(userId), searchAsyncExecutor)
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);

        if (filter.hasKeyword()) {
            List<ClubSearchResult> searchResults = searchWithKeyword(filter);
            List<Long> joinedClubIds = joinedFuture.join();
            return convertSearchResultsWithJoinStatus(searchResults, joinedClubIds);
        } else {
            List<ClubWithMemberCount> resultList = searchWithMysql(filter);
            List<Long> joinedClubIds = joinedFuture.join();
            return convertToClubResponseDto(resultList, joinedClubIds);
        }
    }

    // 함께하는 멤버들의 다른 모임 조회
    // sampleSize: 홈 화면 노출용 랜덤 샘플 수 (DB 조회 크기 아님)
    @Transactional(readOnly = true)
    @Cacheable(value = "teammatesClubs",
            key = "T(org.springframework.security.core.context.SecurityContextHolder).context.authentication.principal.userId + '_' + #page + '_' + #sampleSize")
    public List<ClubResponseDto> getClubsByTeammates(int page, int sampleSize) {
        PageRequest pageRequest = PageRequest.of(page, DEFAULT_PAGE_SIZE);
        Long userId = userService.getCurrentUserId();
        List<ClubWithMemberCount> resultList = clubRepository.findClubsByTeammates(userId, pageRequest);

        return convertToClubResponseDto(sampleForHome(resultList, sampleSize));
    }

    private List<ClubResponseDto> convertToClubResponseDto(List<ClubWithMemberCount> results) {
        return convertToClubResponseDto(results, List.of());
    }

    private List<ClubResponseDto> convertToClubResponseDto(List<ClubWithMemberCount> results, List<Long> joinedClubIds) {
        return results.stream().map(row -> {
            boolean isJoined = joinedClubIds.contains(row.club().getClubId());
            return ClubResponseDto.from(row.club(), row.memberCount(), isJoined);
        }).toList();
    }

    // 내 모임 목록 조회
    @Transactional(readOnly = true)
    public MyMeetingListResponseDto getMyClubs() {
        User user = userService.getCurrentUser();
        List<ClubResponseDto> clubResponseDtoList = userClubRepository.findMyClubsWithInterest(user.getUserId())
                .stream()
                .map(uc -> ClubResponseDto.from(uc.getClub(), uc.getClub().getMemberCount(), true))
                .toList();

        boolean isUnsettledScheduleExist =
                userSettlementRepository.existsByUserAndSettlementStatusNot(user, SettlementStatus.COMPLETED);
        return new MyMeetingListResponseDto(isUnsettledScheduleExist, clubResponseDtoList);
    }

    // 키워드 검색 — SearchPort 위임 (ES 또는 MySQL FULLTEXT)
    private List<ClubSearchResult> searchWithKeyword(SearchFilterDto filter) {
        String keyword = filter.keyword().trim();
        Pageable pageable = createPageable(filter);

        String city = filter.hasLocation() ? filter.city().trim() : null;
        String district = filter.hasLocation() ? filter.district().trim() : null;

        return searchPort.search(keyword, city, district, filter.interestId(), pageable);
    }

    // MySQL 검색 메서드 (키워드 없는 필터 검색)
    private List<ClubWithMemberCount> searchWithMysql(SearchFilterDto filter) {
        PageRequest pageRequest = PageRequest.of(filter.page(), DEFAULT_PAGE_SIZE);

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
            return List.of();
        }
    }

    // 검색 결과를 ClubResponseDto로 변환 (가입 상태 포함)
    private List<ClubResponseDto> convertSearchResultsWithJoinStatus(List<ClubSearchResult> results, List<Long> joinedClubIds) {
        return results.stream().map(result -> {
            boolean isJoined = joinedClubIds.contains(result.clubId());
            return new ClubResponseDto(
                    result.clubId(),
                    result.name(),
                    result.description(),
                    result.interest(),
                    result.district(),
                    result.memberCount(),
                    result.image(),
                    isJoined
            );
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

        return PageRequest.of(filter.page(), DEFAULT_PAGE_SIZE, sort);
    }

    // 홈 화면용 랜덤 샘플링 (상위 20개 중 최대 5개)
    private List<ClubWithMemberCount> sampleForHome(List<ClubWithMemberCount> list, int size) {
        if (size != HOME_SAMPLE_SIZE || list.isEmpty()) {
            return list;
        }
        List<ClubWithMemberCount> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(HOME_SAMPLE_SIZE, shuffled.size()));
    }

    // 사용자의 지역 정보가 유효한지 확인
    private boolean hasValidLocation(User user) {
        return user.getCity() != null && !user.getCity().trim().isEmpty() &&
               user.getDistrict() != null && !user.getDistrict().trim().isEmpty();
    }
}
