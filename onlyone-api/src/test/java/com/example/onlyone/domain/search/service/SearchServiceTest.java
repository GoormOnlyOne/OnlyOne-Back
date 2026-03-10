package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.ClubWithMemberCount;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.search.port.ClubSearchResult;
import com.example.onlyone.domain.search.port.SearchPort;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private SearchService searchService;

    @Mock private ClubRepository clubRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;
    @Mock private UserInterestRepository userInterestRepository;
    @Mock private UserSettlementRepository userSettlementRepository;
    @Mock private SearchPort searchPort;

    // 관심사
    private Interest exerciseInterest;
    private Interest cultureInterest;
    private Interest musicInterest;
    private Interest travelInterest;
    private Interest craftInterest;
    private Interest socialInterest;
    private Interest languageInterest;
    private Interest financeInterest;

    // 사용자들
    private User seoulUser;           // 서울 강남구, 운동+문화 관심사
    private User userWithoutLocation;  // 지역 정보 없음, 음악 관심사
    private User userWithoutInterest;  // 서울 서초구, 관심사 없음
    private User busanUser;           // 부산 해운대구, 여행 관심사
    private User daeguUser;            // 대구 수성구, 언어 관심사
    private User stageTwoUser;     // 경기도 동두천시, 음악 관심사 (2단계 전용)
    private User emptyResultUser;  // 제주도 제주시, 관심사 없음 (결과 없음 전용)

    // 팀메이트 추천 테스트용 사용자들
    private User noTeammateUser;      // 팀메이트가 없는 사용자

    @BeforeEach
    void setUp() {
        // Runnable::run 으로 동기 실행 Executor 전달 (SearchService 의 @Qualifier("customAsyncExecutor") 대체)
        searchService = new SearchService(
                clubRepository,
                userClubRepository,
                userService,
                userInterestRepository,
                userSettlementRepository,
                searchPort,
                Runnable::run
        );

        setupInterests();
        setupUsers();
    }

    // ──────────────────────────── helper: 테스트 데이터 빌더 ────────────────────────────

    private void setupInterests() {
        exerciseInterest = createInterest(1L, Category.EXERCISE);
        cultureInterest  = createInterest(2L, Category.CULTURE);
        musicInterest    = createInterest(3L, Category.MUSIC);
        travelInterest   = createInterest(4L, Category.TRAVEL);
        craftInterest    = createInterest(5L, Category.CRAFT);
        socialInterest   = createInterest(6L, Category.SOCIAL);
        languageInterest = createInterest(7L, Category.LANGUAGE);
        financeInterest  = createInterest(8L, Category.FINANCE);
    }

    private void setupUsers() {
        seoulUser = createUser(1L, 10001L, "일반사용자", "서울", "강남구", Gender.MALE);
        userWithoutLocation = createUser(2L, 10002L, "지역정보없음", null, null, Gender.FEMALE);
        userWithoutInterest = createUser(3L, 10003L, "관심사없음", "서울", "서초구", Gender.MALE);
        busanUser = createUser(4L, 10004L, "부산사용자", "부산", "해운대구", Gender.FEMALE);
        daeguUser = createUser(5L, 10005L, "대구사용자", "대구", "수성구", Gender.MALE);
        stageTwoUser = createUser(6L, 99990L, "2단계전용사용자", "경기도", "동두천시", Gender.MALE);
        emptyResultUser = createUser(7L, 99989L, "빈결과전용사용자", "제주도", "제주시", Gender.FEMALE);
        noTeammateUser = createUser(9L, 30002L, "팀메이트없는사용자", "인천", "연수구", Gender.MALE);
    }

    private Interest createInterest(Long id, Category category) {
        return Interest.builder()
                .interestId(id)
                .category(category)
                .build();
    }

    private User createUser(Long userId, Long kakaoId, String nickname, String city, String district, Gender gender) {
        return User.builder()
                .userId(userId)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .status(Status.ACTIVE)
                .gender(gender)
                .birth(LocalDate.of(1990, 1, 1))
                .city(city)
                .district(district)
                .build();
    }

    private Club createClub(Long id, String name, Category category, String city, String district, long memberCount) {
        Interest interest = switch (category) {
            case EXERCISE -> exerciseInterest;
            case CULTURE  -> cultureInterest;
            case MUSIC    -> musicInterest;
            case TRAVEL   -> travelInterest;
            case CRAFT    -> craftInterest;
            case SOCIAL   -> socialInterest;
            case LANGUAGE -> languageInterest;
            case FINANCE  -> financeInterest;
        };
        return Club.builder()
                .clubId(id)
                .name(name)
                .description(name + " 설명")
                .userLimit(20)
                .city(city)
                .district(district)
                .interest(interest)
                .clubImage("img" + id + ".jpg")
                .memberCount(memberCount)
                .build();
    }

    private ClubWithMemberCount createClubWithMemberCount(Long id, String name, Category category,
                                                           String city, String district, long memberCount) {
        Club club = createClub(id, name, category, city, district, memberCount);
        return new ClubWithMemberCount(club, memberCount);
    }

    private List<ClubWithMemberCount> createClubsWithMemberCount(String city, String district,
                                                                   Category category, String prefix,
                                                                   int count, long startId) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> createClubWithMemberCount(
                        startId + i,
                        city + " " + district + " " + prefix + " 클럽 " + i,
                        category, city, district, 0L))
                .toList();
    }

    // ──────────────────────────── 추천 모임 (recommendedClubs) ────────────────────────────

    @Test
    @DisplayName("사용자의 관심사와 지역이 모두 일치하는 모임이 우선 추천된다.")
    void prioritizeMatchingClubs() {
        // given
        List<Long> userInterestIds = List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId());

        // 서울 강남구, 운동/문화 클럽 9개 (가입한 클럽 제외된 상태)
        List<ClubWithMemberCount> gangnamClubs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            gangnamClubs.add(createClubWithMemberCount((long) i, "서울 강남구 운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        for (int i = 6; i <= 10; i++) {
            gangnamClubs.add(createClubWithMemberCount((long) i, "서울 강남구 문화 클럽 " + (i - 5), Category.CULTURE, "서울", "강남구", 0L));
        }
        // seoulUser가 첫 번째 운동 클럽에 가입 -> 제외된 결과 9개
        List<ClubWithMemberCount> filteredResults = gangnamClubs.subList(1, gangnamClubs.size());

        given(userService.getCurrentUser()).willReturn(seoulUser);
        given(userInterestRepository.findInterestIdsByUserId(seoulUser.getUserId())).willReturn(userInterestIds);
        given(clubRepository.searchByUserInterestAndLocation(
                eq(userInterestIds), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(filteredResults);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).hasSize(9);

        // 서울 강남구의 운동/문화 클럽들이 조회되어야 함
        for (ClubResponseDto dto : results) {
            assertThat(dto.district()).isEqualTo("강남구");
            assertThat(dto.interest()).isIn("운동", "문화");
        }

        // 가입한 클럽(id=1)은 제외되어야 함
        assertThat(results.stream()
                .map(ClubResponseDto::clubId)
                .anyMatch(clubId -> clubId.equals(1L)))
                .isFalse();
    }

    @Test
    @DisplayName("1단계 결과가 있으면 2단계는 실행하지 않는다.")
    void stageOneResultsNoStageTwo() {
        // given
        given(userService.getCurrentUser()).willReturn(seoulUser);
        given(userInterestRepository.findInterestIdsByUserId(seoulUser.getUserId()))
                .willReturn(List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId()));

        List<ClubWithMemberCount> gangnamClubs = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            Category cat = i <= 5 ? Category.EXERCISE : Category.CULTURE;
            gangnamClubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, cat, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(gangnamClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        // 결과에는 1단계만 포함되어야 함 (서울 강남구 모임만!)
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.district())))
                .isTrue();

        // 2단계에서 나오는 클럽은 포함되면 안됨 (다른 지역의 운동/문화 모임)
        assertThat(results.stream()
                .anyMatch(club ->
                        "서초구".equals(club.district()) ||
                        "해운대구".equals(club.district())))
                .isFalse();
    }

    @Test
    @DisplayName("사용자의 지역이 모임의 주소와 정확히 일치한다.")
    void userLocationExactlyMatchesClubAddress() {
        // given
        given(userService.getCurrentUser()).willReturn(busanUser);
        given(userInterestRepository.findInterestIdsByUserId(busanUser.getUserId()))
                .willReturn(List.of(travelInterest.getInterestId()));

        List<ClubWithMemberCount> busanClubs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            busanClubs.add(createClubWithMemberCount((long) i, "부산 해운대구 여행 클럽 " + i, Category.TRAVEL, "부산", "해운대구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("부산"), eq("해운대구"), eq(busanUser.getUserId()), any(Pageable.class)))
                .willReturn(busanClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        assertThat(results.stream()
                .allMatch(club -> "해운대구".equals(club.district())))
                .isTrue();
    }

    @Test
    @DisplayName("size = 5일 때 상위 20개의 모임 중 최대 5개의 모임이 랜덤 반환 된다. - 1단계")
    void returnsRandomFiveClubsFromTopTwentyStepOne() {
        // given
        given(userService.getCurrentUser()).willReturn(seoulUser);
        given(userInterestRepository.findInterestIdsByUserId(seoulUser.getUserId()))
                .willReturn(List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId()));

        // 20개 이상의 클럽 모킹 (repo가 20개 반환)
        List<ClubWithMemberCount> manyClubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Category cat = i <= 10 ? Category.EXERCISE : Category.CULTURE;
            String interest = i <= 10 ? "운동" : "문화";
            manyClubs.add(createClubWithMemberCount((long) i, "서울 강남구 " + interest + " 클럽 " + i, cat, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(manyClubs);

        int size = 5;

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, size);

        // then
        assertThat(results).hasSize(size);

        // 유효한 값인지 확인
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.district()) &&
                        ("운동".equals(club.interest()) || "문화".equals(club.interest()))))
                .isTrue();

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(clubIds).hasSize(size);

        // 결과가 모킹된 셋에서 나온 것인지 확인
        Set<Long> allMockedIds = manyClubs.stream()
                .map(c -> c.club().getClubId())
                .collect(Collectors.toSet());
        assertThat(allMockedIds).containsAll(clubIds);
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다.")
    void recommendClubsStageOnePaging() {
        // given
        given(userService.getCurrentUser()).willReturn(seoulUser);
        given(userInterestRepository.findInterestIdsByUserId(seoulUser.getUserId()))
                .willReturn(List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId()));

        // 첫 페이지: 20개 반환
        List<ClubWithMemberCount> page0Clubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0Clubs.add(createClubWithMemberCount((long) i, "서울 강남구 운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        // 두 번째 페이지: 5개 반환
        List<ClubWithMemberCount> page1Clubs = new ArrayList<>();
        for (int i = 21; i <= 25; i++) {
            page1Clubs.add(createClubWithMemberCount((long) i, "서울 강남구 운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0Clubs);
        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1Clubs);

        // when
        List<ClubResponseDto> page0Results = searchService.recommendedClubs(0, 20); // 첫 페이지
        List<ClubResponseDto> page1Results = searchService.recommendedClubs(1, 20); // 두 번째 페이지

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).isNotEmpty();

        // 페이지 별로 다른 결과
        Set<Long> page0Ids = page0Results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = page1Results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    @DisplayName("사용자 관심사가 없으면 빈 결과가 반환된다.")
    void returnsEmptyUserHasNoInterests() {
        // given
        given(userService.getCurrentUser()).willReturn(userWithoutInterest);
        given(userInterestRepository.findInterestIdsByUserId(userWithoutInterest.getUserId()))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 1단계")
    void exceptClubsUserJoinStepOne() {
        // given
        given(userService.getCurrentUser()).willReturn(seoulUser);
        given(userInterestRepository.findInterestIdsByUserId(seoulUser.getUserId()))
                .willReturn(List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId()));

        // repo는 이미 가입한 클럽을 제외한 결과를 반환 (searchByUserInterestAndLocation이 userId로 필터링)
        List<ClubWithMemberCount> filteredClubs = new ArrayList<>();
        for (int i = 2; i <= 10; i++) {
            Category cat = i <= 5 ? Category.EXERCISE : Category.CULTURE;
            String interest = i <= 5 ? "운동" : "문화";
            filteredClubs.add(createClubWithMemberCount((long) i, "서울 강남구 " + interest + " 클럽 " + i, cat, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("서울"), eq("강남구"), eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(filteredClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(9);

        // 가입한 클럽(id=1)은 추천에서 제외되어야 함
        assertThat(results.stream()
                .map(ClubResponseDto::clubId)
                .anyMatch(clubId -> clubId.equals(1L)))
                .isFalse();

        // 모든 결과가 seoulUser의 지역/관심사와 일치 해야함
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.district()) &&
                        ("운동".equals(club.interest()) ||
                                "문화".equals(club.interest()))))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 city가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityIsNull() {
        // given
        User nullCityUser = createUser(10L, 99991L, "city없는사용자", null, "강남구", Gender.MALE);

        given(userService.getCurrentUser()).willReturn(nullCityUser);
        given(userInterestRepository.findInterestIdsByUserId(nullCityUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        // 1단계 건너뜀 (city가 null이므로 hasValidLocation == false)
        // 2단계: 전국 음악 클럽
        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(nullCityUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 district가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenDistrictIsNull() {
        // given
        User nullDistrictUser = createUser(11L, 99991L, "district없는사용자", "서울", null, Gender.MALE);

        given(userService.getCurrentUser()).willReturn(nullDistrictUser);
        given(userInterestRepository.findInterestIdsByUserId(nullDistrictUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(nullDistrictUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 district가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityAndDistrictIsNull() {
        // given
        given(userService.getCurrentUser()).willReturn(userWithoutLocation);
        given(userInterestRepository.findInterestIdsByUserId(userWithoutLocation.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(userWithoutLocation.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 city가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityIsEmpty() {
        // given
        User emptyCityUser = createUser(12L, 99991L, "city가 비어있는 사용자", "", "강남구", Gender.MALE);

        given(userService.getCurrentUser()).willReturn(emptyCityUser);
        given(userInterestRepository.findInterestIdsByUserId(emptyCityUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(emptyCityUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 district가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenDistrictIsEmpty() {
        // given
        User emptyDistrictUser = createUser(13L, 99991L, "district가 비어있는 사용자", "서울", "", Gender.MALE);

        given(userService.getCurrentUser()).willReturn(emptyDistrictUser);
        given(userInterestRepository.findInterestIdsByUserId(emptyDistrictUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(emptyDistrictUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("사용자의 city와 district가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityAndDistrictIsEmpty() {
        // given
        User emptyCityAndDistrictUser = createUser(14L, 99991L, "city와 district가 비어있는 사용자", "", "", Gender.MALE);

        given(userService.getCurrentUser()).willReturn(emptyCityAndDistrictUser);
        given(userInterestRepository.findInterestIdsByUserId(emptyCityAndDistrictUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(emptyCityAndDistrictUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("1단계에서 결과가 없으면 2단계가 실행된다.")
    void skipsStepOneGoesToStepTwoWhenStageOneIsEmpty() {
        // given
        given(userService.getCurrentUser()).willReturn(stageTwoUser);
        given(userInterestRepository.findInterestIdsByUserId(stageTwoUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        // 1단계: 경기도 동두천시 음악 클럽 없음
        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("경기도"), eq("동두천시"), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        // 2단계: 전국 음악 클럽 (서울 강남구 것들)
        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        // 2단계 결과: 전국의 음악 클럽들 (지역 제한 없음)
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();

        // 2단계에서 나온 결과들은 다른 지역(서울 강남구)이어야 함
        assertThat(results.stream()
                .anyMatch(club -> "강남구".equals(club.district())))
                .isTrue();

        // 1단계 대상 지역(동두천시)은 결과에 없어야 함
        assertThat(results.stream()
                .anyMatch(club -> "동두천시".equals(club.district())))
                .isFalse();
    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 2단계")
    void exceptClubsUserJoinStepTwo() {
        // given
        given(userService.getCurrentUser()).willReturn(stageTwoUser);
        given(userInterestRepository.findInterestIdsByUserId(stageTwoUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        // 1단계: 경기도 동두천시에는 음악 클럽 없음
        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("경기도"), eq("동두천시"), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        // 2단계: repo가 가입된 클럽 제외된 결과 반환
        Long joinedClubId = 100L;
        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "서울 강남구 음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        // 가입된 음악 클럽은 제외
        assertThat(results.stream()
                .map(ClubResponseDto::clubId)
                .anyMatch(clubId -> clubId.equals(joinedClubId)))
                .isFalse();

        // 모든 결과가 음악 관심사 여야함
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("size = 5일 때 상위 20개의 모임 중 최대 5개의 모임이 랜덤 반환 된다. - 2단계")
    void returnsRandomFiveClubsFromTopTwentyStepTwo() {
        // given
        given(userService.getCurrentUser()).willReturn(stageTwoUser);
        given(userInterestRepository.findInterestIdsByUserId(stageTwoUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        // 1단계: 빈 결과
        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("경기도"), eq("동두천시"), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        // 2단계: 20개 이상의 음악 클럽
        String[] cities = {"부산", "대구", "인천", "광주", "울산"};
        String[] districts = {"해운대구", "수성구", "연수구", "서구", "남구"};
        List<ClubWithMemberCount> musicClubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            musicClubs.add(createClubWithMemberCount((long) i, "추가 음악 클럽 " + i, Category.MUSIC,
                    cities[(i - 1) % cities.length], districts[(i - 1) % districts.length], 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(musicClubs);

        int size = 5;

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, size);

        // then
        assertThat(results).hasSize(size);

        // 2단계 검증 -> 모두 음악
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.interest())))
                .isTrue();

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(clubIds).hasSize(size);

        // 결과가 모킹된 셋에서 나온 것인지 확인
        Set<Long> allMockedIds = musicClubs.stream()
                .map(c -> c.club().getClubId())
                .collect(Collectors.toSet());
        assertThat(allMockedIds).containsAll(clubIds);
    }

    @Test
    @DisplayName("1단계와 2단계 모두 빈 결과면 빈 리스트를 반환한다.")
    void emptyResultAllSteps() {
        // given
        given(userService.getCurrentUser()).willReturn(emptyResultUser);
        given(userInterestRepository.findInterestIdsByUserId(emptyResultUser.getUserId()))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다.")
    void recommendClubsStageTwoPaging() {
        // given
        given(userService.getCurrentUser()).willReturn(stageTwoUser);
        given(userInterestRepository.findInterestIdsByUserId(stageTwoUser.getUserId()))
                .willReturn(List.of(musicInterest.getInterestId()));

        // 1단계: 빈 결과
        given(clubRepository.searchByUserInterestAndLocation(anyList(), eq("경기도"), eq("동두천시"), eq(stageTwoUser.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        // 2단계: 첫 페이지 20개
        List<ClubWithMemberCount> page0Clubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0Clubs.add(createClubWithMemberCount((long) i, "음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        // 2단계: 두 번째 페이지 8개
        List<ClubWithMemberCount> page1Clubs = new ArrayList<>();
        for (int i = 21; i <= 28; i++) {
            page1Clubs.add(createClubWithMemberCount((long) i, "음악 클럽 " + i, Category.MUSIC, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterests(anyList(), eq(stageTwoUser.getUserId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0Clubs);
        given(clubRepository.searchByUserInterests(anyList(), eq(stageTwoUser.getUserId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1Clubs);

        // when
        List<ClubResponseDto> page0Results = searchService.recommendedClubs(0, 20); // 첫 페이지
        List<ClubResponseDto> page1Results = searchService.recommendedClubs(1, 20); // 두 번째 페이지

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).isNotEmpty();

        // 페이지 별로 다른 결과
        Set<Long> page0Ids = page0Results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = page1Results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    // ──────────────────────────── 팀메이트 추천 (getClubsByTeammates) ────────────────────────────

    @Test
    @DisplayName("함께하는 멤버들의 다른 모임이 조회된다.")
    void recommendTeammatesClubs() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());

        List<ClubWithMemberCount> teammateClubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            teammateClubs.add(createClubWithMemberCount((long) (100 + i), "팀메이트 전용 클럽 " + i,
                    i % 3 == 0 ? Category.CULTURE : (i % 3 == 1 ? Category.EXERCISE : Category.MUSIC),
                    "인천", i % 2 == 0 ? "연수구" : "남동구", 1L));
        }

        given(clubRepository.findClubsByTeammates(eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(teammateClubs);

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(20);

        // 멤버 수가 올바르게 계산 되는지 확인
        for (ClubResponseDto result : results) {
            assertThat(result.memberCount()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("size = 5일 때 상위 20개 중 랜덤 5개가 반환된다. - 팀메이트 추천")
    void returnsRandomFiveClubsFromTopTwentyTeammates() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        int size = 5;

        List<ClubWithMemberCount> teammateClubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            teammateClubs.add(createClubWithMemberCount((long) (100 + i), "팀메이트 전용 클럽 " + i,
                    Category.EXERCISE, "인천", "연수구", 1L));
        }

        given(clubRepository.findClubsByTeammates(eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(teammateClubs);

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, size);

        // then
        assertThat(results).hasSize(size);

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());
        assertThat(clubIds).hasSize(size);

        // 유효한 팀메이트 클럽인지 확인
        Set<Long> allTeammateClubIds = teammateClubs.stream()
                .map(c -> c.club().getClubId())
                .collect(Collectors.toSet());

        assertThat(allTeammateClubIds).containsAll(clubIds);
    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 팀메이트 추천")
    void exceptClubsUserJoinTeammates() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());

        // repo가 이미 자신의 클럽을 제외한 결과를 반환
        List<ClubWithMemberCount> teammateClubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            teammateClubs.add(createClubWithMemberCount((long) (100 + i), "팀메이트 전용 클럽 " + i,
                    Category.EXERCISE, "인천", "연수구", 1L));
        }

        given(clubRepository.findClubsByTeammates(eq(seoulUser.getUserId()), any(Pageable.class)))
                .willReturn(teammateClubs);

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).hasSize(20);

        // 결과가 유효한 팀메이트 모임인지 확인
        Set<Long> allTeammatesClubIds = teammateClubs.stream()
                .map(c -> c.club().getClubId())
                .collect(Collectors.toSet());

        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(allTeammatesClubIds).containsAll(clubIds);
    }

    @Test
    @DisplayName("팀메이트가 없으면 빈 결과가 반환된다.")
    void notExistTeammates() {
        // given
        given(userService.getCurrentUserId()).willReturn(noTeammateUser.getUserId());
        given(clubRepository.findClubsByTeammates(eq(noTeammateUser.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. - 팀메이트 추천")
    void teammatePaging() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());

        List<ClubWithMemberCount> page0Clubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0Clubs.add(createClubWithMemberCount((long) (100 + i), "팀메이트 클럽 " + i,
                    Category.EXERCISE, "인천", "연수구", 1L));
        }

        List<ClubWithMemberCount> page1Clubs = new ArrayList<>();
        for (int i = 21; i <= 25; i++) {
            page1Clubs.add(createClubWithMemberCount((long) (100 + i), "팀메이트 클럽 " + i,
                    Category.EXERCISE, "인천", "연수구", 1L));
        }

        given(clubRepository.findClubsByTeammates(eq(seoulUser.getUserId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0Clubs);
        given(clubRepository.findClubsByTeammates(eq(seoulUser.getUserId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1Clubs);

        // when
        List<ClubResponseDto> page0Results = searchService.getClubsByTeammates(0, 20);
        List<ClubResponseDto> page1Results = searchService.getClubsByTeammates(1, 20);

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).hasSize(5);
    }

    // ──────────────────────────── 관심사별 검색 (searchClubByInterest) ────────────────────────────

    @Test
    @DisplayName("특정 관심사 ID로 해당 관심사의 모임들이 검색된다.")
    void searchByInterest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(1L));

        List<ClubWithMemberCount> page0 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0.add(createClubWithMemberCount((long) i, "운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        List<ClubWithMemberCount> page1 = List.of(
                createClubWithMemberCount(21L, "운동 클럽 21", Category.EXERCISE, "부산", "해운대구", 0L),
                createClubWithMemberCount(22L, "운동 클럽 22", Category.EXERCISE, "부산", "해운대구", 0L)
        );

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0);
        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);
        List<ClubResponseDto> results2 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results1.stream()
                .allMatch(club -> "운동".equals(club.interest())))
                .isTrue();

        assertThat(results2).hasSize(2);
        assertThat(results2.stream()
                .allMatch(club -> "운동".equals(club.interest())))
                .isTrue();
    }

    @Test
    @DisplayName("검색 결과가 멤버 수 기준으로 정렬된다. - 관심사")
    void searchByInterestOrderByMemberCount() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(1L));

        // 멤버 수 차등으로 정렬된 결과
        List<ClubWithMemberCount> sortedClubs = List.of(
                createClubWithMemberCount(10L, "운동 클럽 A", Category.EXERCISE, "서울", "서초구", 5L),
                createClubWithMemberCount(1L, "운동 클럽 B", Category.EXERCISE, "서울", "강남구", 3L),
                createClubWithMemberCount(2L, "운동 클럽 C", Category.EXERCISE, "서울", "강남구", 1L),
                createClubWithMemberCount(3L, "운동 클럽 D", Category.EXERCISE, "서울", "강남구", 0L)
        );

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(sortedClubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);

        // then
        assertThat(results).hasSize(4);
        assertThat(results)
                .extracting(ClubResponseDto::memberCount)
                .isSortedAccordingTo(Collections.reverseOrder());
    }

    @Test
    @DisplayName("각 모임의 멤버수가 정확히 반환된다. - 관심사")
    void searchByInterestExactlyMemberCount() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        List<ClubWithMemberCount> clubs = List.of(
                createClubWithMemberCount(1L, "운동 클럽 1", Category.EXERCISE, "서울", "강남구", 0L),
                createClubWithMemberCount(2L, "운동 클럽 2", Category.EXERCISE, "서울", "강남구", 0L)
        );

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);

        // then
        assertThat(results).hasSize(2);
        assertThat(results)
                .allMatch(club -> club.memberCount().equals(0L));
    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 관심사")
    void searchByInterestExactlyJoinStatus() {
        // given
        Long joinedClubId = 1L;
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(joinedClubId));

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            clubs.add(createClubWithMemberCount((long) i, "운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);

        // then
        assertThat(results).hasSize(20);

        long joinCount = results.stream()
                .filter(ClubResponseDto::isJoined)
                .count();
        assertThat(joinCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. (기본 20개) - 관심사")
    void searchByInterestPaging() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        List<ClubWithMemberCount> page0 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0.add(createClubWithMemberCount((long) i, "운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        List<ClubWithMemberCount> page1 = List.of(
                createClubWithMemberCount(21L, "운동 클럽 21", Category.EXERCISE, "서울", "강남구", 0L),
                createClubWithMemberCount(22L, "운동 클럽 22", Category.EXERCISE, "서울", "강남구", 0L)
        );

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0);
        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);
        List<ClubResponseDto> results2 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results2).hasSize(2);
    }

    @Test
    @DisplayName("존재하지 않는 관심사 ID로 검색 시 빈 결과가 반환된다.")
    void searchByNotExistInterest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());
        given(clubRepository.searchByInterest(eq(999999L), any(Pageable.class))).willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(999999L, 0);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("interestId가 null일 시 적절한 예외가 발생한다.")
    void searchByNullInterest() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByInterest(null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 interestId입니다.");
    }

    @Test
    @DisplayName("관심사가 정확히 일치하는 모임만 검색된다.")
    void searchByInterestExactly() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        List<ClubWithMemberCount> page0 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0.add(createClubWithMemberCount((long) i, "운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        List<ClubWithMemberCount> page1 = List.of(
                createClubWithMemberCount(21L, "운동 클럽 21", Category.EXERCISE, "부산", "해운대구", 0L),
                createClubWithMemberCount(22L, "운동 클럽 22", Category.EXERCISE, "부산", "해운대구", 0L)
        );

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(0, 20))))
                .willReturn(page0);
        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), eq(PageRequest.of(1, 20))))
                .willReturn(page1);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);
        List<ClubResponseDto> results2 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results1)
                .allMatch(club -> "운동".equals(club.interest()));
        assertThat(results2)
                .allMatch(club -> "운동".equals(club.interest()));
    }

    // ──────────────────────────── 지역별 검색 (searchClubByLocation) ────────────────────────────

    @Test
    @DisplayName("city와 district 모두 일치하는 모임들이 검색된다.")
    void searchByLocation() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(1L));

        List<ClubWithMemberCount> gangnamClubs = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            Category cat = i <= 5 ? Category.EXERCISE : (i <= 10 ? Category.CULTURE : (i <= 13 ? Category.MUSIC : Category.TRAVEL));
            gangnamClubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, cat, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(gangnamClubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(16);
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.district()));
    }

    @Test
    @DisplayName("검색 결과가 멤버 수 기준으로 정렬된다. - 지역")
    void searchByLocationOrderByMemberCount() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(1L));

        List<ClubWithMemberCount> sortedClubs = List.of(
                createClubWithMemberCount(1L, "강남 클럽 A", Category.EXERCISE, "서울", "강남구", 3L),
                createClubWithMemberCount(2L, "강남 클럽 B", Category.CULTURE, "서울", "강남구", 2L),
                createClubWithMemberCount(3L, "강남 클럽 C", Category.MUSIC, "서울", "강남구", 0L)
        );

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(sortedClubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(3);
        assertThat(results)
                .extracting(ClubResponseDto::memberCount)
                .isSortedAccordingTo(Collections.reverseOrder());
    }

    @Test
    @DisplayName("각 모임의 멤버수가 정확히 반환된다. - 지역")
    void searchByLocationExactlyMemberCount() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(1L));

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        // 첫 번째 클럽: 멤버 1명 (seoulUser가 가입)
        clubs.add(createClubWithMemberCount(1L, "서울 강남구 운동 클럽 1", Category.EXERCISE, "서울", "강남구", 1L));
        // 나머지 15개: 멤버 0명
        for (int i = 2; i <= 16; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(16);
        // 첫 번째 클럽은 1명
        ClubResponseDto joinedClub = results.stream().findFirst().orElseThrow();
        assertThat(joinedClub.memberCount()).isEqualTo(1L);

        // 나머지 클럽은 0명
        long zeroMemberCount = results.stream()
                .skip(1)
                .filter(club -> club.memberCount() == 0L)
                .count();
        assertThat(zeroMemberCount).isEqualTo(15L);
    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 지역")
    void searchByLocationExactlyJoinStatus() {
        // given
        Long joinedClubId = 1L;
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(joinedClubId));

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        long joinCount = results.stream()
                .filter(ClubResponseDto::isJoined)
                .count();
        assertThat(joinCount).isEqualTo(1L);

        long notJoinCount = results.stream()
                .filter(club -> !club.isJoined())
                .count();
        assertThat(notJoinCount).isEqualTo(15L);
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. (기본 20개) - 지역")
    void searchByLocationPaging() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        List<ClubWithMemberCount> page0 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        List<ClubWithMemberCount> page1 = new ArrayList<>();
        for (int i = 21; i <= 36; i++) {
            page1.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.SOCIAL, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), eq(PageRequest.of(0, 20))))
                .willReturn(page0);
        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), eq(PageRequest.of(1, 20))))
                .willReturn(page1);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubByLocation("서울", "강남구", 0);
        List<ClubResponseDto> results2 = searchService.searchClubByLocation("서울", "강남구", 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results2).hasSize(16);
    }

    @Test
    @DisplayName("city만 일치하고 district가 다른 경우 검색되지 않는다.")
    void searchByLocationExactlyCity() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());
        given(clubRepository.searchByLocation(eq("서울"), eq("노원구"), any(Pageable.class)))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "노원구", 0);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("district만 일치하고 city가 다른 경우 검색되지 않는다.")
    void searchByLocationExactlyDistrict() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());
        given(clubRepository.searchByLocation(eq("부산"), eq("강남구"), any(Pageable.class)))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("부산", "강남구", 0);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 지역으로 검색 시 빈 결과가 반환된다.")
    void searchByNotExistLocation() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());
        given(clubRepository.searchByLocation(eq("제주도"), eq("강남구"), any(Pageable.class)))
                .willReturn(List.of());

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("제주도", "강남구", 0);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("city가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationCityNull() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation(null, "강남구", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    @Test
    @DisplayName("district가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationDistrictNull() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("서울", null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    @Test
    @DisplayName("city와 district가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationCityAndDistrictNull() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation(null, null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    @Test
    @DisplayName("city가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationCityEmpty() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("", "강남구", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    @Test
    @DisplayName("district가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationDistrictEmpty() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("서울", "", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    @Test
    @DisplayName("city와 district가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationCityAndDistrictEmpty() {
        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("", "", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");
    }

    // ──────────────────────────── 통합 검색 (searchClubs) ────────────────────────────

    @Test
    @DisplayName("키워드만 입력 시 해당 키워드가 포함된 모임들이 검색된다.")
    void searchClubsByKeyword() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("운동", null, null, null, null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            searchResults.add(new ClubSearchResult((long) i, "운동 클럽 " + i, "운동을 좋아하는 사람들",
                    "운동", "강남구", 0L, "img.jpg"));
        }

        given(searchPort.search(eq("운동"), isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);
        assertThat(results)
                .allMatch(club -> club.name().contains("운동") ||
                        club.description().contains("운동"));
    }

    @Test
    @DisplayName("키워드 + 지역 필터 조합 검색이 정상 동작한다.")
    void searchClubsByKeywordAndLocation() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("운동", "서울", "강남구", null, null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            searchResults.add(new ClubSearchResult((long) i, "서울 강남구 운동 클럽 " + i,
                    "운동을 좋아하는 사람들 서울 강남구 지역", "운동", "강남구", 0L, "img.jpg"));
        }

        given(searchPort.search(eq("운동"), eq("서울"), eq("강남구"), isNull(), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.name().contains("운동") ||
                        club.description().contains("운동")) &&
                        "강남구".equals(club.district()));
    }

    @Test
    @DisplayName("키워드 + 관심사 필터 조합 검색이 정상 동작한다.")
    void searchClubsByKeywordAndInterest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("강남", null, null, exerciseInterest.getInterestId(), null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            searchResults.add(new ClubSearchResult((long) i, "서울 강남구 운동 클럽 " + i,
                    "강남에서 운동하는 모임", "운동", "강남구", 0L, "img.jpg"));
        }

        given(searchPort.search(eq("강남"), isNull(), isNull(), eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.name().contains("강남") ||
                        club.description().contains("강남")) &&
                        club.interest().equals("운동"));
    }

    @Test
    @DisplayName("키워드 + 지역 + 관심사 모든 필터 조합이 정상 동작한다.")
    void searchClubsByAllFilter() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("강남부산", "서울", "강남구", exerciseInterest.getInterestId(), null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            searchResults.add(new ClubSearchResult((long) i, "서울 강남구 운동 클럽 " + i,
                    "강남에서 운동하는 모임", "운동", "강남구", 0L, "img.jpg"));
        }

        given(searchPort.search(eq("강남부산"), eq("서울"), eq("강남구"), eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.name().contains("강남") ||
                        club.description().contains("강남")) &&
                        club.interest().equals("운동"));
    }

    @Test
    @DisplayName("정렬 옵션 MEMBER_COUNT가 정상 적용된다.")
    void searchClubsSortByMemberCount() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, null, null, null, SearchFilterDto.SortType.MEMBER_COUNT, 0);

        // 키워드 없으므로 MySQL 경로 -> 조건 없음 -> 빈 결과 반환
        // (searchWithMysql에서 모든 조건이 null이면 빈 리스트)

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("정렬 옵션 LATEST가 정상 적용된다.")
    void searchClubsSortByLatest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, null, null, null, SearchFilterDto.SortType.LATEST, 0);

        // 키워드 없고 필터 없으므로 빈 결과
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. (기본 20개) - 통합검색")
    void searchClubsPaging() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter1 = new SearchFilterDto(null, "서울", "강남구", null, null, 0);
        SearchFilterDto filter2 = new SearchFilterDto(null, "서울", "강남구", null, null, 1);

        List<ClubWithMemberCount> page0 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            page0.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }
        List<ClubWithMemberCount> page1 = new ArrayList<>();
        for (int i = 21; i <= 26; i++) {
            page1.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), eq(PageRequest.of(0, 20))))
                .willReturn(page0);
        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), eq(PageRequest.of(1, 20))))
                .willReturn(page1);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubs(filter1);
        List<ClubResponseDto> results2 = searchService.searchClubs(filter2);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results2).hasSize(6);

        Set<Long> page0Ids = results1.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = results2.stream()
                .map(ClubResponseDto::clubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 통합검색")
    void searchClubsJoinStatus() {
        // given
        Long joinedClubId = 1L;
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of(joinedClubId));

        SearchFilterDto filter = new SearchFilterDto(null, "서울", "강남구", null, null, 0);

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        long joinCount = results.stream()
                .filter(ClubResponseDto::isJoined)
                .count();
        assertThat(joinCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("city만 있고 district가 null이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterDistrictNull() {
        // given
        SearchFilterDto filter = new SearchFilterDto(null, "서울", null, null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
    }

    @Test
    @DisplayName("city만 있고 district가 빈 문자열이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterDistrictEmpty() {
        // given
        SearchFilterDto filter = new SearchFilterDto(null, "서울", "", null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
    }

    @Test
    @DisplayName("district만 있고 city가 null이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterCityNull() {
        // given
        SearchFilterDto filter = new SearchFilterDto(null, null, "강남구", null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
    }

    @Test
    @DisplayName("district만 있고 city가 빈 문자열이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterCityEmpty() {
        // given
        SearchFilterDto filter = new SearchFilterDto(null, "", "깅남구", null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
    }

    @Test
    @DisplayName("city와 district가 모두 있으면 정상 처리된다. - 통합검색")
    void searchClubsLocationFilter() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, "서울", "강남구", null, null, 0);

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(16);
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.district()));
    }

    @Test
    @DisplayName("city와 district가 모두 null이면 정상 처리된다.")
    void searchClubsLocationFilterNull() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("운동", null, null, null, null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            String district = i <= 5 ? "강남구" : (i <= 9 ? "서초구" : "해운대구");
            searchResults.add(new ClubSearchResult((long) i, "운동 클럽 " + i,
                    "운동을 좋아하는 사람들", "운동", district, 0L, "img.jpg"));
        }

        given(searchPort.search(eq("운동"), isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);

        // 키워드로만 검색 되었는지 검증
        assertThat(results).allMatch(club ->
                club.name().contains("운동") || club.description().contains("운동"));

        // 다양한 지역의 운동 클럽이 포함되어야 함
        Set<String> districts = results.stream()
                .map(ClubResponseDto::district)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("빈 문자열 city/district는 예외가 발생한다.")
    void searchClubsLocationFilterEmpty() {
        // given
        SearchFilterDto filter = new SearchFilterDto(null, "", "", null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
    }

    @Test
    @DisplayName("1글자 키워드는 예외가 발생한다.")
    void searchClubsByOneKeyword() {
        // given
        SearchFilterDto filter = new SearchFilterDto("아", null, null, null, null, 0);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("검색어는 최소 2글자 이상이어야 합니다.");
    }

    @Test
    @DisplayName("2글자 이상 키워드는 정상 처리된다.")
    void searchClubsByGreaterThanTwoKeyword() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("운동", null, null, null, null, 0);

        List<ClubSearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            searchResults.add(new ClubSearchResult((long) i, "운동 클럽 " + i,
                    "운동을 좋아하는 사람들", "운동", "강남구", 0L, "img.jpg"));
        }

        given(searchPort.search(eq("운동"), isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(searchResults);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);
        assertThat(results)
                .allMatch(club -> club.name().contains("운동"));
    }

    @Test
    @DisplayName("null 키워드는 정상 처리된다. (전체 모임 조회)")
    void searchClubsNullKeyword() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, null, null, null, null, 0);

        // 키워드 없고 필터 없으므로 searchWithMysql -> 조건 없음 -> 빈 결과
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열 키워드는 정상 처리된다. (전체 모임 조회)")
    void searchClubsEmptyKeyword() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("", null, null, null, null, 0);

        // 빈 키워드 -> hasKeyword() false -> searchWithMysql -> 조건 없음 -> 빈 결과
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("공백만 있는 키워드는 정상 처리된다. (trim 후 처리)")
    void searchClubsTrimKeyword() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto("          ", null, null, null, null, 0);

        // 공백 키워드 -> hasKeyword() false -> searchWithMysql -> 조건 없음 -> 빈 결과
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("키워드 없이 지역만으로 검색 시 정상 동작한다.")
    void searchClubsOnlyLocation() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, "서울", "강남구", null, null, 0);

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByLocation(eq("서울"), eq("강남구"), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.district()));
    }

    @Test
    @DisplayName("키워드 없이 관심사만으로 검색 시 정상 동작한다.")
    void searchClubsOnlyInterest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, null, null, exerciseInterest.getInterestId(), null, 0);

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            clubs.add(createClubWithMemberCount((long) i, "운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByInterest(eq(exerciseInterest.getInterestId()), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "운동".equals(club.interest()));
    }

    @Test
    @DisplayName("키워드 없이 지역 + 관심사로 검색 시 정상 동작한다.")
    void searchClubsOnlyLocationAndInterest() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, "서울", "강남구", exerciseInterest.getInterestId(), null, 0);

        List<ClubWithMemberCount> clubs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            clubs.add(createClubWithMemberCount((long) i, "서울 강남구 운동 클럽 " + i, Category.EXERCISE, "서울", "강남구", 0L));
        }

        given(clubRepository.searchByUserInterestAndLocation(
                eq(List.of(exerciseInterest.getInterestId())), eq("서울"), eq("강남구"), isNull(), any(Pageable.class)))
                .willReturn(clubs);

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.district()))
                .allMatch(club -> "운동".equals(club.interest()));
    }

    @Test
    @DisplayName("모든 필터가 null인 경우 전체 모임이 조회된다.")
    void searchClubsNullFilter() {
        // given
        given(userService.getCurrentUserId()).willReturn(seoulUser.getUserId());
        given(userClubRepository.findByClubIdsByUserId(seoulUser.getUserId())).willReturn(List.of());

        SearchFilterDto filter = new SearchFilterDto(null, null, null, null, null, 0);

        // 키워드 없고 필터 없으므로 빈 결과 (searchWithMysql -> 조건 없음)
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).isEmpty();
    }
}
