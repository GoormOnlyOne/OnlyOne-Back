package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
class SearchServiceTest {

    @Autowired private SearchService searchService;
    @Autowired private ClubRepository clubRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserClubRepository userClubRepository;
    @Autowired private InterestRepository interestRepository;
    @Autowired private UserInterestRepository userInterestRepository;
    @Autowired private UserSettlementRepository userSettlementRepository;

    @MockitoBean private UserService userService;

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
    private User teammateUser;        // seoulUser와 함께하는 팀메이트
    private User noTeammateUser;      // 팀메이트가 없는 사용자

    // 클럽들 - 각 지역/관심사별로 충분한 수량
    private List<Club> seoulGangnamClubs = new ArrayList<>();
    private List<Club> seoulSeochoClubs = new ArrayList<>();
    private List<Club> seoulMapoClubs = new ArrayList<>();
    private List<Club> busanClubs = new ArrayList<>();
    private List<Club> daeguClubs = new ArrayList<>();
    private List<Club> incheonClubs = new ArrayList<>();

    // 팀메이트 추천 테스트용 클럽들
    private Club sharedClub;              // seoulUser와 teammateUser가 공통으로 가입한 클럽
    private List<Club> teammateOtherClubs = new ArrayList<>();  // teammateUser만 가입한 다른 클럽들

    private Pageable pageable;

    @BeforeEach
    void setUp() {
        pageable = PageRequest.of(0, 20);
        setupInterests();
        setupUsers();
        setupClubs();
        setupUserInterests();
        setupUserClubMemberships();
        setupTeammateTestData();
    }

    @AfterEach
    void tearDown() {
        userInterestRepository.deleteAll();
        userClubRepository.deleteAll();
        clubRepository.deleteAll();
        userRepository.deleteAll();
        interestRepository.deleteAll();
    }

    private void setupInterests() {
        Interest culture = Interest.builder().category(Category.CULTURE).build();
        Interest exercise = Interest.builder().category(Category.EXERCISE).build();
        Interest travel = Interest.builder().category(Category.TRAVEL).build();
        Interest music = Interest.builder().category(Category.MUSIC).build();
        Interest craft = Interest.builder().category(Category.CRAFT).build();
        Interest social = Interest.builder().category(Category.SOCIAL).build();
        Interest language = Interest.builder().category(Category.LANGUAGE).build();
        Interest finance = Interest.builder().category(Category.FINANCE).build();

        List<Interest> allInterests = interestRepository.saveAll(List.of(
                culture, exercise, travel, music, craft, social, language, finance));

        exerciseInterest = allInterests.stream().filter(i -> i.getCategory() == Category.EXERCISE).findFirst().orElseThrow();
        cultureInterest = allInterests.stream().filter(i -> i.getCategory() == Category.CULTURE).findFirst().orElseThrow();
        musicInterest = allInterests.stream().filter(i -> i.getCategory() == Category.MUSIC).findFirst().orElseThrow();
        travelInterest = allInterests.stream().filter(i -> i.getCategory() == Category.TRAVEL).findFirst().orElseThrow();
        craftInterest = allInterests.stream().filter(i -> i.getCategory() == Category.CRAFT).findFirst().orElseThrow();
        socialInterest = allInterests.stream().filter(i -> i.getCategory() == Category.SOCIAL).findFirst().orElseThrow();
        languageInterest = allInterests.stream().filter(i -> i.getCategory() == Category.LANGUAGE).findFirst().orElseThrow();
        financeInterest = allInterests.stream().filter(i -> i.getCategory() == Category.FINANCE).findFirst().orElseThrow();
    }

    private void setupUsers() {
        // 일반 사용자 (서울 강남구, 관심사: 운동+문화)
        seoulUser = User.builder()
                .kakaoId(10001L)
                .nickname("일반사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("강남구")
                .build();

        // 지역 정보 없는 사용자 (관심사: 음악)
        userWithoutLocation = User.builder()
                .kakaoId(10002L)
                .nickname("지역정보없음")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1995, 5, 15))
                .city(null)
                .district(null)
                .build();

        // 관심사 없는 사용자 (서울 서초구)
        userWithoutInterest = User.builder()
                .kakaoId(10003L)
                .nickname("관심사없음")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1992, 3, 10))
                .city("서울")
                .district("서초구")
                .build();

        // 부산 사용자 (부산 해운대구, 관심사: 여행)
        busanUser = User.builder()
                .kakaoId(10004L)
                .nickname("부산사용자")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1988, 12, 25))
                .city("부산")
                .district("해운대구")
                .build();

        // 대구 사용자 (대구 수성구, 관심사: 언어)
        daeguUser = User.builder()
                .kakaoId(10005L)
                .nickname("대구사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1985, 7, 20))
                .city("대구")
                .district("수성구")
                .build();

        // 2단계 전용 사용자 (경기도 동두천시, 음악 관심사)
        stageTwoUser = User.builder()
                .kakaoId(99990L)
                .nickname("2단계전용사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("경기도")
                .district("동두천시")
                .build();

        // 빈 결과 전용 사용자 (제주도 제주시, 관심사 없음)
        emptyResultUser = User.builder()
                .kakaoId(99989L)
                .nickname("빈결과전용사용자")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1992, 6, 15))
                .city("제주도")
                .district("제주시")
                .build();

        // 팀메이트 추천 테스트용 사용자들
        teammateUser = User.builder()
                .kakaoId(30001L)
                .nickname("팀메이트사용자")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1991, 4, 10))
                .city("서울")
                .district("강남구")
                .build();

        noTeammateUser = User.builder()
                .kakaoId(30002L)
                .nickname("팀메이트없는사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1987, 9, 5))
                .city("인천")
                .district("연수구")
                .build();

        userRepository.saveAll(List.of(seoulUser, userWithoutLocation, userWithoutInterest, busanUser, daeguUser, stageTwoUser, emptyResultUser, teammateUser, noTeammateUser));
    }

    private void setupClubs() {
        // 서울 강남구 클럽들 (각 관심사별 5개씩)
        seoulGangnamClubs.addAll(createClubsForLocation("서울", "강남구", exerciseInterest, "운동", 5));
        seoulGangnamClubs.addAll(createClubsForLocation("서울", "강남구", cultureInterest, "문화", 5));
        seoulGangnamClubs.addAll(createClubsForLocation("서울", "강남구", musicInterest, "음악", 3));
        seoulGangnamClubs.addAll(createClubsForLocation("서울", "강남구", travelInterest, "여행", 3));

        // 서울 서초구 클럽들
        seoulSeochoClubs.addAll(createClubsForLocation("서울", "서초구", exerciseInterest, "운동", 4));
        seoulSeochoClubs.addAll(createClubsForLocation("서울", "서초구", cultureInterest, "문화", 4));
        seoulSeochoClubs.addAll(createClubsForLocation("서울", "서초구", socialInterest, "사교", 3));

        // 서울 마포구 클럽들
        seoulMapoClubs.addAll(createClubsForLocation("서울", "마포구", craftInterest, "공예", 3));
        seoulMapoClubs.addAll(createClubsForLocation("서울", "마포구", languageInterest, "언어", 3));

        // 부산 클럽들
        busanClubs.addAll(createClubsForLocation("부산", "해운대구", exerciseInterest, "운동", 4));
        busanClubs.addAll(createClubsForLocation("부산", "해운대구", travelInterest, "여행", 4));
        busanClubs.addAll(createClubsForLocation("부산", "중구", cultureInterest, "문화", 3));

        // 대구 클럽들
        daeguClubs.addAll(createClubsForLocation("대구", "수성구", languageInterest, "언어", 3));
        daeguClubs.addAll(createClubsForLocation("대구", "수성구", financeInterest, "재테크", 3));

        // 인천 클럽들
        incheonClubs.addAll(createClubsForLocation("인천", "연수구", socialInterest, "사교", 3));

        // 모든 클럽 저장
        List<Club> allClubs = new ArrayList<>();
        allClubs.addAll(seoulGangnamClubs);
        allClubs.addAll(seoulSeochoClubs);
        allClubs.addAll(seoulMapoClubs);
        allClubs.addAll(busanClubs);
        allClubs.addAll(daeguClubs);
        allClubs.addAll(incheonClubs);

        clubRepository.saveAll(allClubs);
    }

    private List<Club> createClubsForLocation(String city, String district, Interest interest, String category, int count) {
        List<Club> clubs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Club club = Club.builder()
                    .name(city + " " + district + " " + category + " 클럽 " + i)
                    .description(category + "을 좋아하는 사람들이 모이는 곳입니다. " + city + " " + district + " 지역")
                    .userLimit(20)
                    .city(city)
                    .district(district)
                    .interest(interest)
                    .clubImage(category.toLowerCase() + i + ".jpg")
                    .build();
            clubs.add(club);
        }
        return clubs;
    }

    private void setupUserInterests() {
        List<UserInterest> userInterests = new ArrayList<>();

        // normalUser: 운동 + 문화
        userInterests.add(UserInterest.builder().user(seoulUser).interest(exerciseInterest).build());
        userInterests.add(UserInterest.builder().user(seoulUser).interest(cultureInterest).build());

        // userWithoutLocation: 음악
        userInterests.add(UserInterest.builder().user(userWithoutLocation).interest(musicInterest).build());

        // busanUser: 여행
        userInterests.add(UserInterest.builder().user(busanUser).interest(travelInterest).build());

        // daeguUser: 언어
        userInterests.add(UserInterest.builder().user(daeguUser).interest(languageInterest).build());

        // stageTwoUser: 음악
        userInterests.add(UserInterest.builder().user(stageTwoUser).interest(musicInterest).build());

        // emptyResultUser에는 관심사를 추가하지 않음 (없는 관심사 시뮬레이션)

        // userWithoutInterest: 관심사 없음 (추가하지 않음)

        userInterestRepository.saveAll(userInterests);
    }

    private void setupUserClubMemberships() {
        List<UserClub> userClubs = new ArrayList<>();

        // seoulUser가 몇 개 클럽에 가입
        if (!seoulGangnamClubs.isEmpty()) {
            userClubs.add(UserClub.builder()
                    .user(seoulUser)
                    .club(seoulGangnamClubs.getFirst()) // 첫 번째 운동 클럽
                    .clubRole(ClubRole.MEMBER)
                    .build());
        }

        // busanUser가 부산 클럽에 가입
        if (!busanClubs.isEmpty()) {
            userClubs.add(UserClub.builder()
                    .user(busanUser)
                    .club(busanClubs.getFirst()) // 첫 번째 부산 클럽
                    .clubRole(ClubRole.LEADER)
                    .build());
        }

        userClubRepository.saveAll(userClubs);
    }

    private void setupTeammateTestData() {
        // 공유 클럽 생성 (seoulUser와 teammateUser가 함께 가입할 클럽 - 기존 테스트에 영향 안주도록 다른 지역)
        sharedClub = Club.builder()
                .name("공유 클럽")
                .description("팀메이트 테스트용 공유 클럽")
                .userLimit(30)
                .city("인천")
                .district("연수구")
                .interest(socialInterest) // 기존 테스트들이 사용하지 않는 관심사
                .clubImage("shared.jpg")
                .build();
        clubRepository.save(sharedClub);

        // teammateUser만 가입한 다른 클럽들 (기존 테스트에 영향주지 않도록 다른 지역에 생성)
        for (int i = 1; i <= 25; i++) {
            Club club = Club.builder()
                    .name("팀메이트 전용 클럽 " + i)
                    .description("팀메이트만 가입한 클럽 " + i)
                    .userLimit(20)
                    .city("인천") // 기존 테스트에 영향 안주도록 인천으로
                    .district(i % 2 == 0 ? "연수구" : "남동구")
                    .interest(i % 3 == 0 ? cultureInterest : (i % 3 == 1 ? exerciseInterest : musicInterest))
                    .clubImage("teammate" + i + ".jpg")
                    .build();
            teammateOtherClubs.add(club);
        }
        clubRepository.saveAll(teammateOtherClubs);

        // 멤버십 설정
        List<UserClub> teammateUserClubs = new ArrayList<>();

        // seoulUser와 teammateUser 모두 공유 클럽에 가입
        teammateUserClubs.add(UserClub.builder()
                .user(seoulUser)
                .club(sharedClub)
                .clubRole(ClubRole.LEADER)
                .build());

        teammateUserClubs.add(UserClub.builder()
                .user(teammateUser)
                .club(sharedClub)
                .clubRole(ClubRole.MEMBER)
                .build());

        // teammateUser가 다른 모든 클럽에 가입
        for (Club club : teammateOtherClubs) {
            teammateUserClubs.add(UserClub.builder()
                    .user(teammateUser)
                    .club(club)
                    .clubRole(ClubRole.MEMBER)
                    .build());
        }

        userClubRepository.saveAll(teammateUserClubs);
    }


    @Test
    @Transactional
    @DisplayName("사용자의 관심사와 지역이 모두 일치하는 모임이 우선 추천된다.")
    void prioritizeMatchingClubs() {
        // given
        List<Long> userInterestIds = List.of(exerciseInterest.getInterestId(), cultureInterest.getInterestId());

        // when
        List<Object[]> results = clubRepository.searchByUserInterestAndLocation(
                userInterestIds,
                seoulUser.getCity(),
                seoulUser.getDistrict(),
                seoulUser.getUserId(),
                pageable
        );

        // then
        assertThat(results).hasSize(9);

        // 서울 강남구의 운동/문화 클럽들이 조회되어야 함
        for (Object[] result : results) {
            Club club = (Club) result[0];
            assertThat(club.getCity()).isEqualTo("서울");
            assertThat(club.getDistrict()).isEqualTo("강남구");
            assertThat(club.getInterest().getCategory())
                    .isIn(Category.EXERCISE, Category.CULTURE);
        }

        // 가입한 클럽은 제외되어야 함 (첫 번째 운동 클럽에 가입되어 있음)
        Club joinedClub = seoulGangnamClubs.getFirst();
        assertThat(results.stream()
                .map(result -> ((Club) result[0]).getClubId())
                .anyMatch(clubId -> clubId.equals(joinedClub.getClubId())))
                .isFalse();
    }

    @Test
    @DisplayName("1단계 결과가 있으면 2단계는 실행하지 않는다.")
    void stageOneResultsNoStageTwo() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        // 결과에는 1단계만 포함되어야 함 (서울 강남구 모임만!)
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.getDistrict())))
                .isTrue();

        // 2단계에서 나오는 클럽은 포함되면 안됨 (다른 지역의 운동/문화 모임)
        assertThat(results.stream()
                .anyMatch(club ->
                        "서초구".equals(club.getDistrict()) ||
                        "해운대구".equals(club.getDistrict())))
                .isFalse();
    }

    @Test
    @DisplayName("사용자의 지역이 모임의 주소와 정확히 일치한다.")
    void userLocationExactlyMatchesClubAddress() {
        // given
        when(userService.getCurrentUser()).thenReturn(busanUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        assertThat(results.stream()
                .allMatch(club -> "해운대구".equals(club.getDistrict())))
                .isTrue();
    }

    @Test
    @DisplayName("size = 5일 때 상위 20개의 모임 중 최대 5개의 모임이 랜덤 반환 된다. - 1단계")
    void returnsRandomFiveClubsFromTopTwentyStepOne() {
        // given
        List<Club> clubs = new ArrayList<>();
        for (int i = 0; i <= 25; i++) {
            Club club = Club.builder()
                    .name("추가 운동 클럽 " + i)
                    .description("셔플 테스트용 클럽")
                    .userLimit(20)
                    .city("서울")
                    .district("강남구")
                    .interest(exerciseInterest)
                    .clubImage("test.jpg")
                    .build();
            clubs.add(club);
        }
        clubRepository.saveAll(clubs);

        int size = 5;
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, size);

        // then
        assertThat(results).hasSize(size);

        // 여러 번 실행해서 랜덤성 확인
        List<ClubResponseDto> results2 = searchService.recommendedClubs(0, size);

        // 유효한 값인지 확인
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.getDistrict()) &&
                        ("운동".equals(club.getInterest()) || "문화".equals(club.getInterest()))))
                .isTrue();

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(clubIds).hasSize(size);

        // results2도 유효한지 확인
        assertThat(results2).hasSize(size);
        assertThat(results2.stream()
                .allMatch(club -> "강남구".equals(club.getDistrict()) &&
                        ("운동".equals(club.getInterest()) ||
                                "문화".equals(club.getInterest()))))
                .isTrue();

        Set<Long> clubIds2 = results2.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());
        assertThat(clubIds2).hasSize(size);

    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다.")
    void recommendClubsStageOnePaging() {
        // given
        List<Club> clubs = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            Club club = Club.builder()
                    .name("추가 운동 클럽 " + i)
                    .description("페이징 테스트용 클럽")
                    .userLimit(20)
                    .city("서울")
                    .district("강남구")
                    .interest(exerciseInterest)
                    .clubImage("test.jpg")
                    .build();
            clubs.add(club);
        }
        clubRepository.saveAll(clubs);

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> page0Results = searchService.recommendedClubs(0, 20); // 첫 페이지
        List<ClubResponseDto> page1Results = searchService.recommendedClubs(1, 20); // 두 번째 페이지

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).isNotEmpty();

        // 페이지 별로 다른 결과
        Set<Long> page0Ids = page0Results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = page1Results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    @DisplayName("사용자 관심사가 없으면 빈 결과가 반환된다.")
    void returnsEmptyUserHasNoInterests() {
        // given
        when(userService.getCurrentUser()).thenReturn(userWithoutInterest);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 1단계")
    void exceptClubsUserJoinStepOne() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        Club joinedClub = seoulGangnamClubs.getFirst();

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(9);

        // 가입한 클럽은 추천에서 제외되어야 함
        assertThat(results.stream()
                .map(ClubResponseDto::getClubId)
                .anyMatch(clubId -> clubId.equals(joinedClub.getClubId())))
                .isFalse();

        // 모든 결과가 seoulUser의 지역/관심사와 일치 해야함
        assertThat(results.stream()
                .allMatch(club -> "강남구".equals(club.getDistrict()) &&
                        ("운동".equals(club.getInterest()) ||
                                "문화".equals(club.getInterest()))))
                .isTrue();

    }

    @Test
    @DisplayName("사용자의 city가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityIsNull() {
        // given
        User nullCityUser = User.builder()
                .kakaoId(99991L)
                .nickname("city없는사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city(null)
                .district("강남구")
                .build();
        userRepository.save(nullCityUser);

        UserInterest userInterest = UserInterest.builder()
                .user(nullCityUser)
                .interest(musicInterest)
                .build();
        userInterestRepository.save(userInterest);

        when(userService.getCurrentUser()).thenReturn(nullCityUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();
      
    }

    @Test
    @DisplayName("사용자의 district가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenDistrictIsNull() {
        // given
        User nullDistrictUser = User.builder()
                .kakaoId(99991L)
                .nickname("district없는사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district(null)
                .build();
        userRepository.save(nullDistrictUser);

        UserInterest userInterest = UserInterest.builder()
                .user(nullDistrictUser)
                .interest(musicInterest)
                .build();
        userInterestRepository.save(userInterest);

        when(userService.getCurrentUser()).thenReturn(nullDistrictUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("사용자의 district가 null인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityAndDistrictIsNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(userWithoutLocation);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("사용자의 city가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityIsEmpty() {
        // given
        User emptyCityUser = User.builder()
                .kakaoId(99991L)
                .nickname("city가 비어있는 사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("")
                .district("강남구")
                .build();
        userRepository.save(emptyCityUser);

        UserInterest userInterest = UserInterest.builder()
                .user(emptyCityUser)
                .interest(musicInterest)
                .build();
        userInterestRepository.save(userInterest);

        when(userService.getCurrentUser()).thenReturn(emptyCityUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("사용자의 district가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenDistrictIsEmpty() {
        // given
        User emptyDistrictUser = User.builder()
                .kakaoId(99991L)
                .nickname("district가 비어있는 사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("")
                .build();
        userRepository.save(emptyDistrictUser);

        UserInterest userInterest = UserInterest.builder()
                .user(emptyDistrictUser)
                .interest(musicInterest)
                .build();
        userInterestRepository.save(userInterest);

        when(userService.getCurrentUser()).thenReturn(emptyDistrictUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("사용자의 city와 district가 빈 문자열인 경우 1단계를 건너뛰고 2단계로 진행된다.")
    void skipsStepOneAndGoesToStepTwoWhenCityAndDistrictIsEmpty() {
        // given
        User emptyCityAndDistrictUser = User.builder()
                .kakaoId(99991L)
                .nickname("district가 비어있는 사용자")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("")
                .build();
        userRepository.save(emptyCityAndDistrictUser);

        UserInterest userInterest = UserInterest.builder()
                .user(emptyCityAndDistrictUser)
                .interest(musicInterest)
                .build();
        userInterestRepository.save(userInterest);

        when(userService.getCurrentUser()).thenReturn(emptyCityAndDistrictUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("1단계에서 결과가 없으면 2단계가 실행된다.")
    void skipsStepOneGoesToStepTwoWhenStageOneIsEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(stageTwoUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();
        // 2단계 결과: 전국의 음악 클럽들 (지역 제한 없음)
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

        // 1단계에서는 경기도 동두천시 음악 클럽이 없으므로
        // 2단계에서 나온 결과들은 다른 지역(서울 강남구)이어야 함
        assertThat(results.stream()
                .anyMatch(club -> "강남구".equals(club.getDistrict())))
                .isTrue();

        // 1단계 대상 지역(동두천시)은 결과에 없어야 함
        assertThat(results.stream()
                .anyMatch(club -> "동두천시".equals(club.getDistrict())))
                .isFalse();

    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 2단계")
    void exceptClubsUserJoinStepTwo() {
        // given
        // stageTwoUser가 음악 클럽 중 하나에 가입
        Club musicClub = seoulGangnamClubs.stream()
                .filter(club -> club.getInterest().getCategory() == Category.MUSIC)
                .findFirst()
                .orElseThrow();

        UserClub userClub = UserClub.builder()
                .user(stageTwoUser)
                .club(musicClub)
                .clubRole(ClubRole.MEMBER)
                .build();
        userClubRepository.save(userClub);

        when(userService.getCurrentUser()).thenReturn(stageTwoUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);

        // then
        assertThat(results).isNotEmpty();

        // 가입된 음악 클럽은 제외
        assertThat(results.stream()
                .map(ClubResponseDto::getClubId)
                .anyMatch(clubId -> clubId.equals(musicClub.getClubId())))
                .isFalse();

        // 모든 결과가 음악 관심사 여야함
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

    }

    @Test
    @DisplayName("size = 5일 때 상위 20개의 모임 중 최대 5개의 모임이 랜덤 반환 된다. - 2단계")
    void returnsRandomFiveClubsFromTopTwentyStepTwo() {
        // given
        List<Club> clubs = new ArrayList<>();
        String[] cities = {"부산", "대구", "인천", "광주", "울산"};
        String[] districts = {"해운대구", "수성구", "연수구", "서구", "남구"};
        for (int i = 0; i <= 25; i++) {
            Club club = Club.builder()
                    .name("추가 음악 클럽 " + i)
                    .description("셔플 테스트용 클럽")
                    .userLimit(20)
                    .city(cities[i % cities.length])
                    .district(districts[i % districts.length])
                    .interest(musicInterest)
                    .clubImage("test.jpg")
                    .build();
            clubs.add(club);
        }
        clubRepository.saveAll(clubs);

        int size = 5;
        when(userService.getCurrentUser()).thenReturn(stageTwoUser);

        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, size);

        // then
        assertThat(results).hasSize(size);

        // 랜덤성 확인
        List<ClubResponseDto> results2 = searchService.recommendedClubs(0, size);

        // 2단계 검증 -> 모두 음악
        assertThat(results.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(clubIds).hasSize(size);

        // results2도 유효한지 확인
        assertThat(results2).hasSize(size);
        assertThat(results2.stream()
                .allMatch(club -> "음악".equals(club.getInterest())))
                .isTrue();

        Set<Long> clubIds2 = results2.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());
        assertThat(clubIds2).hasSize(size);
    }
    
    @Test
    @DisplayName("1단계와 2단계 모두 빈 결과면 빈 리스트를 반환한다.")
    void emptyResultAllSteps() {
        // given
        when(userService.getCurrentUser()).thenReturn(emptyResultUser);
        
        // when
        List<ClubResponseDto> results = searchService.recommendedClubs(0, 20);
        
        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다.")
    void recommendClubsStageTwoPaging() {
        // given
        List<Club> clubs = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            Club club = Club.builder()
                    .name("추가 음악 클럽 " + i)
                    .description("페이징 테스트용 클럽")
                    .userLimit(20)
                    .city("서울")
                    .district("강남구")
                    .interest(musicInterest)
                    .clubImage("test.jpg")
                    .build();
            clubs.add(club);
        }
        clubRepository.saveAll(clubs);

        when(userService.getCurrentUser()).thenReturn(stageTwoUser);

        // when
        List<ClubResponseDto> page0Results = searchService.recommendedClubs(0, 20); // 첫 페이지
        List<ClubResponseDto> page1Results = searchService.recommendedClubs(1, 20); // 두 번째 페이지

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).isNotEmpty();

        // 페이지 별로 다른 결과
        Set<Long> page0Ids = page0Results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = page1Results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    @DisplayName("함께하는 멤버들의 다른 모임이 조회된다.")
    void recommendTeammatesClubs() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(20); // teammateUser가 25개 클럽에 가입했지만 페이징 20개

        // seoulUser 자신도 있는 클럽은 제외되어야 함
        assertThat(results.stream()
                .map(ClubResponseDto::getClubId)
                .anyMatch(clubId -> clubId.equals(sharedClub.getClubId())))
                .isFalse();

        // 결과가 teammateUser의 다른 클럽들 중에서 나온 것인지 확인 (페이징으로 20개만)
        Set<Long> allTeammateClubIds = teammateOtherClubs.stream()
                .map(Club::getClubId)
                .collect(Collectors.toSet());

        Set<Long> resultClubIds = results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        // 결과의 모든 클럽이 teammateUser의 클럽 중 하나인지 확인
        assertThat(allTeammateClubIds).containsAll(resultClubIds);

        // 멤버 수가 올바르게 계산 되는지 확인 (공유 클럽에 seoulUser 가입 안되어 있으면 다 teammateUser 1명만 담겨 있음)
        for (ClubResponseDto result : results) {
            assertThat(result.getMemberCount()).isEqualTo(1L);
        }

    }

    @Test
    @DisplayName("size = 5일 때 상위 20개 중 랜덤 5개가 반환된다. - 팀메이트 추천")
    void returnsRandomFiveClubsFromTopTwentyTeammates() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        int size = 5;

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, size);

        // then
        assertThat(results).hasSize(size);

        List<ClubResponseDto> results2 = searchService.getClubsByTeammates(0, size);

        assertThat(results2).hasSize(size);

        // 중복 없는지 확인
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());
        assertThat(clubIds).hasSize(size);

        // 유효한 팀메이트 클럽인지 확인
        Set<Long> allTeammateClubIds = teammateOtherClubs.stream()
                .map(Club::getClubId)
                .collect(Collectors.toSet());

        assertThat(allTeammateClubIds).containsAll(clubIds); // 첫 번째 결과 확인
        assertThat(allTeammateClubIds).containsAll(results2.stream() // 두 번째 결과 확인
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("자신이 가입한 모임은 추천에서 제외된다. - 팀메이트 추천")
    void exceptClubsUserJoinTeammates() {
        // given
        Club teammateClub = teammateOtherClubs.getFirst();
        UserClub userClub = UserClub.builder()
                .user(seoulUser)
                .club(teammateClub)
                .clubRole(ClubRole.MEMBER)
                .build();

        userClubRepository.save(userClub);

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).hasSize(20); // 총 25개인데 1개 가입해서 24개 이므로 페이징으로 자른 20개

        // seoulUser가 가입된 모임은 모두 제외되어야함
        assertThat(results.stream()
                .map(ClubResponseDto::getClubId)
                .anyMatch(clubId -> clubId.equals(sharedClub.getClubId()) ||
                        clubId.equals(teammateClub.getClubId())))
                .isFalse();

        // 결과가 유효한 팀메이트 모임인지 확인
        Set<Long> allTeammatesClubIds = teammateOtherClubs.stream()
                .map(Club::getClubId)
                .collect(Collectors.toSet());
        
        Set<Long> clubIds = results.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(allTeammatesClubIds).containsAll(clubIds);


    }
    
    @Test
    @DisplayName("팀메이트가 없으면 빈 결과가 반환된다.")
    void notExistTeammates() {
        // given
        when(userService.getCurrentUser()).thenReturn(noTeammateUser);
        
        // when
        List<ClubResponseDto> results = searchService.getClubsByTeammates(0, 20);

        // then
        assertThat(results).isEmpty();
      
    }
    
    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. - 팀메이트 추천")
    void teammatePaging() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> page0Results = searchService.getClubsByTeammates(0, 20);
        List<ClubResponseDto> page1Results = searchService.getClubsByTeammates(1, 20);

        // then
        assertThat(page0Results).hasSize(20);
        assertThat(page1Results).hasSize(5);
    }
    
    @Test
    @DisplayName("특정 관심사 ID로 해당 관심사의 모임들이 검색된다.")
    void searchByInterest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        // when
        List<ClubResponseDto> results1 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);
        List<ClubResponseDto> results2 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results1.stream()
                .allMatch(club -> "운동".equals(club.getInterest())))
                .isTrue();

        assertThat(results2).hasSize(2);
        assertThat(results2.stream()
                .allMatch(club -> "운동".equals(club.getInterest())))
                .isTrue();
    }
    
    @Test
    @DisplayName("검색 결과가 멤버 수 기준으로 정렬된다. - 관심사")
    void searchByInterestOrderByMemberCount() {
        // given
        // 하나 클럽에 멤버를 2명 추가하여 차등 만들기
        Club club = seoulSeochoClubs.getFirst(); // 운동 클럽 하나 선택
        UserClub userClub1 = UserClub.builder()
                .user(daeguUser)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        UserClub userClub2 = UserClub.builder()
                .user(userWithoutLocation)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        userClubRepository.saveAll(List.of(userClub1, userClub2));

        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);

        // then
        assertThat(results).hasSize(20);
        assertThat(results)
                .extracting(ClubResponseDto::getMemberCount)
                .isSortedAccordingTo(Collections.reverseOrder());
    }

    @Test
    @DisplayName("각 모임의 멤버수가 정확히 반환된다. - 관심사")
    void searchByInterestExactlyMemberCount() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results).hasSize(2);
        assertThat(results)
                .allMatch(club -> club.getMemberCount().equals(0L));

    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 관심사")
    void searchByInterestExactlyJoinStatus() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

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
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
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
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.searchClubByInterest(999999L, 0);

        // then
        assertThat(results).isEmpty();

    }
    
    @Test
    @DisplayName("interestId가 null일 시 적절한 예외가 발생한다.")
    void searchByNullInterest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByInterest(null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 interestId입니다.");
    }
    
    @Test
    @DisplayName("관심사가 정확히 일치하는 모임만 검색된다.")
    void searchByInterestExactly() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> results1 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 0);
        List<ClubResponseDto> results2 = searchService.searchClubByInterest(exerciseInterest.getInterestId(), 1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results1)
                .allMatch(club -> "운동".equals(club.getInterest()));
        assertThat(results2)
                .allMatch(club -> "운동".equals(club.getInterest()));

    }
    
    @Test
    @DisplayName("city와 district 모두 일치하는 모임들이 검색된다.")
    void searchByLocation() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(16);
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.getDistrict()));
      
    }

    @Test
    @DisplayName("검색 결과가 멤버 수 기준으로 정렬된다. - 지역")
    void searchByLocationOrderByMemberCount() {
        // given
        // 하나 클럽에 멤버를 2명 추가하여 차등 만들기
        Club club = seoulGangnamClubs.getFirst(); // 운동 클럽 하나 선택
        UserClub userClub1 = UserClub.builder()
                .user(daeguUser)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        UserClub userClub2 = UserClub.builder()
                .user(userWithoutLocation)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        userClubRepository.saveAll(List.of(userClub1, userClub2));

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(16);
        assertThat(results)
                .extracting(ClubResponseDto::getMemberCount)
                .isSortedAccordingTo(Collections.reverseOrder());
    }

    @Test
    @DisplayName("각 모임의 멤버수가 정확히 반환된다. - 지역")
    void searchByLocationExactlyMemberCount() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "강남구", 0);

        // then
        assertThat(results).hasSize(16);
        // seoulUser가 가입한 첫번째 운동 클럽은 1명
        ClubResponseDto joinedClub = results.stream().findFirst().orElseThrow();
        assertThat(joinedClub.getMemberCount()).isEqualTo(1L);

        // 나머지 클럽은 0명
        long zeroMemberCount = results.stream()
                .filter(club -> !club.getName().contains("운동 클럽 1"))
                .filter(club -> club.getMemberCount() == 0L)
                .count();
        assertThat(zeroMemberCount).isEqualTo(15L);
    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 지역")
    void searchByLocationExactlyJoinStatus() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

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
        List<Club> clubs = new ArrayList<>();
        for (int i =0; i < 20; i++) {
            Club club = Club.builder()
                    .name("서울 강남구 클럽 " + i)
                    .description("서울 강남구 클럽")
                    .userLimit(30)
                    .city("서울")
                    .district("강남구")
                    .interest(socialInterest) // 기존 테스트들이 사용하지 않는 관심사
                    .clubImage("shared.jpg")
                    .build();

            clubs.add(club);
        }

        clubRepository.saveAll(clubs);

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results1 = searchService.searchClubByLocation("서울", "강남구",  0);
        List<ClubResponseDto> results2 = searchService.searchClubByLocation("서울", "강남구",  1);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results2).hasSize(16);

    }
    
    @Test
    @DisplayName("city만 일치하고 district가 다른 경우 검색되지 않는다.")
    void searchByLocationExactlyCity() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("서울", "노원구", 0);

        // then
        assertThat(results).isEmpty();
      
    }
    
    @Test
    @DisplayName("district만 일치하고 city가 다른 경우 검색되지 않는다.")
    void searchByLocationExactlyDistrict() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        
        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("부산", "강남구", 0);

        // then
        assertThat(results).isEmpty();
      
    }

    @Test
    @DisplayName("존재하지 않는 지역으로 검색 시 빈 결과가 반환된다.")
    void searchByNotExistLocation() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when
        List<ClubResponseDto> results = searchService.searchClubByLocation("제주도", "강남구", 0);

        // then
        assertThat(results).isEmpty();

    }

    @Test
    @DisplayName("city가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationCityNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation(null, "강남구", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("district가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationDistrictNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("서울", null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("city와 district가 null일 시 적절한 예외가 발생한다.")
    void searchByLocationCityAndDistrictNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation(null, null, 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("city가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationCityEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("", "강남구", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("district가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationDistrictEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("서울", "", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("city와 district가 빈 문자열일 시 적절한 예외가 발생한다.")
    void searchByLocationCityAndDistrictEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        // when & then
        assertThatThrownBy(() -> searchService.searchClubByLocation("", "", 0))
                .isInstanceOf(CustomException.class)
                .hasMessage("유효하지 않은 city 또는 district입니다.");

    }

    @Test
    @DisplayName("키워드만 입력 시 해당 키워드가 포함된 모임들이 검색된다.")
    void searchClubsByKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("운동")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);
        assertThat(results)
                .allMatch(club -> club.getName().contains("운동") ||
                        club.getDescription().contains("운동"));
      
    }
    
    @Test
    @DisplayName("키워드 + 지역 필터 조합 검색이 정상 동작한다.")
    void searchClubsByKeywordAndLocation() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("운동")
                .city("서울")
                .district("강남구")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);
        
        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.getName().contains("운동") ||
                        club.getDescription().contains("운동")) &&
                        "강남구".equals(club.getDistrict()));
      
    }
    
    @Test
    @DisplayName("키워드 + 관심사 필터 조합 검색이 정상 동작한다.")
    void searchClubsByKeywordAndInterest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("강남")
                .interestId(exerciseInterest.getInterestId())
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.getName().contains("강남") ||
                        club.getDescription().contains("강남")) &&
                        club.getInterest().equals("운동"));
      
    }

    @Test
    @DisplayName("키워드 + 지역 + 관심사 모든 필터 조합이 정상 동작한다.")
    void searchClubsByAllFilter() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("강남부산") // 부산 + 운동인 거도 키워드에서 검색이 되지만 지역 필터링에서 걸러짐
                .city("서울")
                .district("강남구")
                .interestId(exerciseInterest.getInterestId())
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(5);
        assertThat(results)
                .allMatch(club -> (club.getName().contains("강남") ||
                        club.getDescription().contains("강남")) &&
                        club.getInterest().equals("운동"));

    }
    
    @Test
    @DisplayName("정렬 옵션 MEMBER_COUNT가 정상 적용된다.")
    void searchClubsSortByMemberCount() {
        // given
        Club club1 = seoulGangnamClubs.getFirst();
        Club club2 = seoulGangnamClubs.get(5);

        userClubRepository.saveAll(List.of(
                UserClub.builder().user(daeguUser).club(club1).clubRole(ClubRole.MEMBER).build(),
                UserClub.builder().user(busanUser).club(club1).clubRole(ClubRole.MEMBER).build(),
                UserClub.builder().user(daeguUser).club(club2).clubRole(ClubRole.MEMBER).build()
        ));

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .sortBy(SearchFilterDto.SortType.MEMBER_COUNT)
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);
        assertThat(results)
                .extracting(ClubResponseDto::getMemberCount)
                .isSortedAccordingTo(Collections.reverseOrder());
      
    }

    @Test
    @DisplayName("정렬 옵션 LATEST가 정상 적용된다.")
    void searchClubsSortByLatest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .sortBy(SearchFilterDto.SortType.LATEST)
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);
        assertThat(results)
                .extracting(ClubResponseDto::getClubId)
                .isSortedAccordingTo(Collections.reverseOrder());

    }
    
    @Test
    @DisplayName("page 파라미터로 페이징이 정상 동작한다. (기본 20개) - 통합검색")
    void searchClubsPaging() {
        // given
        List<Club> clubs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Club club = Club.builder()
                    .name("서울 강남구 페이징 테스트 클럽 " + i)
                    .description("페이징 테스트 클럽")
                    .userLimit(20)
                    .city("서울")
                    .district("강남구")
                    .interest(exerciseInterest)
                    .clubImage(".jpg")
                    .build();
            clubs.add(club);
        }

        clubRepository.saveAll(clubs);

        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter1 = SearchFilterDto.builder()
                .page(0)
                .city("서울")
                .district("강남구")
                .build();

        SearchFilterDto filter2 = SearchFilterDto.builder()
                .page(1)
                .city("서울")
                .district("강남구")
                .build();

        // when
        List<ClubResponseDto> results1 = searchService.searchClubs(filter1);
        List<ClubResponseDto> results2 = searchService.searchClubs(filter2);

        // then
        assertThat(results1).hasSize(20);
        assertThat(results2).hasSize(6);

        Set<Long> page0Ids = results1.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        Set<Long> page1Ids = results2.stream()
                .map(ClubResponseDto::getClubId)
                .collect(Collectors.toSet());

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

      
    }

    @Test
    @DisplayName("사용자의 가입 상태가 정확히 반영된다. - 통합검색")
    void searchClubsJoinStatus() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .page(0)
                .city("서울")
                .district("강남구")
                .build();
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
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("서울")
                .district(null)
                .build();

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");

    }

    @Test
    @DisplayName("city만 있고 district가 빈 문자열이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterDistrictEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("서울")
                .district("")
                .build();

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");

    }

    @Test
    @DisplayName("district만 있고 city가 null이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterCityNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city(null)
                .district("강남구")
                .build();

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");

    }

    @Test
    @DisplayName("district만 있고 city가 빈 문자열이면 예외가 발생한다. - 통합검색")
    void searchClubsFilterCityEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("")
                .district("깅남구")
                .build();

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");

    }

    @Test
    @DisplayName("city와 district가 모두 있으면 정상 처리된다. - 통합검색")
    void searchClubsLocationFilter() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("서울")
                .district("강남구")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(16);
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.getDistrict()));

    }
    
    @Test
    @DisplayName("city와 district가 모두 null이면 정상 처리된다.")
    void searchClubsLocationFilterNull() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("운동")
                .city(null)
                .district(null)
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);

        // 키워드로만 검색 되었는지 검증
        assertThat(results).allMatch(club ->
                club.getName().contains("운동") || club.getDescription().contains("운동"));

        // 다양한 지역의 운동 클럽이 포함되어야 함
        Set<String> districts = results.stream()
                .map(ClubResponseDto::getDistrict)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);
    }
    
    @Test
    @DisplayName("빈 문자열 city/district는 예외가 발생한다.")
    void searchClubsLocationFilterEmpty() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("")
                .district("")
                .build();

        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("지역 필터는 city와 district가 모두 제공되어야 합니다.");
      
    }

    @Test
    @DisplayName("1글자 키워드는 예외가 발생한다.")
    void searchClubsByOneKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("아")
                .build();
    
        // when & then
        assertThatThrownBy(() -> searchService.searchClubs(filter))
                .isInstanceOf(CustomException.class)
                .hasMessage("검색어는 최소 2글자 이상이어야 합니다.");
    }
    
    @Test
    @DisplayName("2글자 이상 키워드는 정상 처리된다.")
    void searchClubsByGreaterThanTwoKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("운동")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(13);
        assertThat(results)
                .allMatch(club -> club.getName().contains("운동"));
          
    }
    
    @Test
    @DisplayName("null 키워드는 정상 처리된다. (전체 모임 조회)")
    void searchClubsNullKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword(null)
                .build();
    
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);

        // 다양한 지역의 모임이 나왔는지
        Set<String> districts = results.stream()
                .map(ClubResponseDto::getDistrict)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);

        // 다양한 카테고리의 모임이 나왔는지
        Set<String> interests = results.stream()
                .map(ClubResponseDto::getInterest)
                .collect(Collectors.toSet());

        assertThat(interests.size()).isGreaterThan(1);
          
    }

    @Test
    @DisplayName("빈 문자열 키워드는 정상 처리된다. (전체 모임 조회)")
    void searchClubsEmptyKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);

        // 다양한 지역의 모임이 나왔는지
        Set<String> districts = results.stream()
                .map(ClubResponseDto::getDistrict)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);

        // 다양한 카테고리의 모임이 나왔는지
        Set<String> interests = results.stream()
                .map(ClubResponseDto::getInterest)
                .collect(Collectors.toSet());

        assertThat(interests.size()).isGreaterThan(1);
        
    }

    @Test
    @DisplayName("공백만 있는 키워드는 정상 처리된다. (trim 후 처리)")
    void searchClubsTrimKeyword() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword("          ")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);

        // 다양한 지역의 모임이 나왔는지
        Set<String> districts = results.stream()
                .map(ClubResponseDto::getDistrict)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);

        // 다양한 카테고리의 모임이 나왔는지
        Set<String> interests = results.stream()
                .map(ClubResponseDto::getInterest)
                .collect(Collectors.toSet());

        assertThat(interests.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("키워드 없이 지역만으로 검색 시 정상 동작한다.")
    void searchClubsOnlyLocation() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .city("서울")
                .district("강남구")
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.getDistrict()));
    }
    
    @Test
    @DisplayName("키워드 없이 관심사만으로 검색 시 정상 동작한다.")
    void searchClubsOnlyInterest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .interestId(exerciseInterest.getInterestId())
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "운동".equals(club.getInterest()));
          
    }

    @Test
    @DisplayName("키워드 없이 지역 + 관심사로 검색 시 정상 동작한다.")
    void searchClubsOnlyLocationAndInterest() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);
        SearchFilterDto filter = SearchFilterDto.builder()
                .city("서울")
                .district("강남구")
                .interestId(exerciseInterest.getInterestId())
                .build();

        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results)
                .allMatch(club -> "강남구".equals(club.getDistrict()))
                .allMatch(club -> "운동".equals(club.getInterest()));

    }
    
    @Test
    @DisplayName("모든 필터가 null인 경우 전체 모임이 조회된다.")
    void searchClubsNullFilter() {
        // given
        when(userService.getCurrentUser()).thenReturn(seoulUser);

        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword(null)
                .city(null)
                .district(null)
                .interestId(null)
                .build();
    
        // when
        List<ClubResponseDto> results = searchService.searchClubs(filter);

        // then
        assertThat(results).hasSize(20);

        // 다양한 지역의 모임이 나왔는지
        Set<String> districts = results.stream()
                .map(ClubResponseDto::getDistrict)
                .collect(Collectors.toSet());

        assertThat(districts.size()).isGreaterThan(1);

        // 다양한 카테고리의 모임이 나왔는지
        Set<String> interests = results.stream()
                .map(ClubResponseDto::getInterest)
                .collect(Collectors.toSet());

        assertThat(interests.size()).isGreaterThan(1);
          
    }
}