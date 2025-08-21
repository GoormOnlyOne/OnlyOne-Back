package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class ClubRepositoryTest {

    @Autowired private ClubRepository clubRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserClubRepository userClubRepository;
    @Autowired private InterestRepository interestRepository;

    private Interest exerciseInterest;
    private Interest cultureInterest;
    private User testUser1;
    private User testUser2;
    private User testUser3;
    private Club exerciseClubInSeoul;
    private Club cultureClubInSeoul;
    private Club exerciseClubInBusan;
    private Pageable pageable;


    @BeforeEach
    void setUp() {
        pageable = PageRequest.of(0, 20);

        // 관심사 데이터 - 8개 카테고리 모두 생성
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

        exerciseInterest = allInterests.stream()
                .filter(i -> i.getCategory() == Category.EXERCISE)
                .findFirst().orElseThrow();
        
        cultureInterest = allInterests.stream()
                .filter(i -> i.getCategory() == Category.CULTURE)
                .findFirst().orElseThrow();

        // 사용자 데이터
        testUser1 = User.builder()
                .kakaoId(12345L)
                .nickname("테스트유저1")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("강남구")
                .build();

        testUser2 = User.builder()
                .kakaoId(12346L)
                .nickname("테스트유저2")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1995, 5, 15))
                .city("서울")
                .district("강남구")
                .build();

        testUser3 = User.builder()
                .kakaoId(12347L)
                .nickname("테스트유저3")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1985, 12, 20))
                .city("부산")
                .district("해운대구")
                .build();

        userRepository.saveAll(List.of(testUser1, testUser2, testUser3));

        // 클럽 데이터
        exerciseClubInSeoul = Club.builder()
                .name("서울 축구 클럽")
                .description("서울에서 함께 축구해요!")
                .userLimit(20)
                .city("서울")
                .district("강남구")
                .interest(exerciseInterest)
                .clubImage("soccer.jpg")
                .build();

        cultureClubInSeoul = Club.builder()
                .name("서울 독서 모임")
                .description("책을 읽고 토론해요")
                .userLimit(15)
                .city("서울")
                .district("강남구")
                .interest(cultureInterest)
                .clubImage("book.jpg")
                .build();

        exerciseClubInBusan = Club.builder()
                .name("부산 테니스 클럽")
                .description("부산에서 테니스 치실 분!")
                .userLimit(12)
                .city("부산")
                .district("해운대구")
                .interest(exerciseInterest)
                .clubImage("tennis.jpg")
                .build();

        clubRepository.saveAll(List.of(exerciseClubInSeoul, cultureClubInSeoul, exerciseClubInBusan));

        // UserClub 관계 설정
        UserClub userClub1 = UserClub.builder()
                .user(testUser1)
                .club(exerciseClubInSeoul)
                .clubRole(ClubRole.LEADER)
                .build();

        UserClub userClub2 = UserClub.builder()
                .user(testUser2)
                .club(exerciseClubInSeoul)
                .clubRole(ClubRole.MEMBER)
                .build();

        UserClub userClub3 = UserClub.builder()
                .user(testUser3)
                .club(exerciseClubInBusan)
                .clubRole(ClubRole.LEADER)
                .build();

        userClubRepository.saveAll(List.of(userClub1, userClub2, userClub3));
    }

    @AfterEach
    void tearDown() {
        userClubRepository.deleteAll();
        clubRepository.deleteAll();
        userRepository.deleteAll();
        interestRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("관심사로 클럽을 검색할 수 있다")
    void searchByInterest() {
        // when
        List<Object[]> results = clubRepository.searchByInterest(exerciseInterest.getInterestId(), pageable);

        // then
        assertThat(results).hasSize(2);
        
        Club club1 = (Club) results.getFirst()[0];
        Long memberCount1 = (Long) results.getFirst()[1];
        
        Club club2 = (Club) results.get(1)[0];
        Long memberCount2 = (Long) results.get(1)[1];

        assertThat(club1.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
        assertThat(club2.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
        
        if (club1.getName().equals("서울 축구 클럽")) {
            assertThat(memberCount1).isEqualTo(2L);
        } else {
            assertThat(memberCount2).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("지역으로 클럽을 검색할 수 있다")
    void searchByLocation() {
        // when
        List<Object[]> results = clubRepository.searchByLocation("서울", "강남구", pageable);

        // then
        assertThat(results).hasSize(2);
        
        for (Object[] result : results) {
            Club club = (Club) result[0];
            assertThat(club.getCity()).isEqualTo("서울");
            assertThat(club.getDistrict()).isEqualTo("강남구");
        }
    }

    @Test
    @Transactional
    @DisplayName("사용자 관심사와 지역으로 맞춤 클럽을 추천할 수 있다")
    void searchByUserInterestAndLocation() {
        // given
        List<Long> userInterestIds = List.of(exerciseInterest.getInterestId());

        // when
        List<Object[]> results = clubRepository.searchByUserInterestAndLocation(
                userInterestIds, "서울", "강남구", testUser3.getUserId(), pageable);

        // then
        assertThat(results).hasSize(1);
        
        Club club = (Club) results.getFirst()[0];
        Long memberCount = (Long) results.getFirst()[1];
        
        assertThat(club.getName()).isEqualTo("서울 축구 클럽");
        assertThat(club.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
        assertThat(club.getCity()).isEqualTo("서울");
        assertThat(club.getDistrict()).isEqualTo("강남구");
        assertThat(memberCount).isEqualTo(2L);
    }

    @Test
    @Transactional
    @DisplayName("사용자 관심사로 클럽을 추천할 수 있다")
    void searchByUserInterests() {
        // given
        List<Long> userInterestIds = List.of(exerciseInterest.getInterestId());

        // when
        List<Object[]> results = clubRepository.searchByUserInterests(userInterestIds, testUser2.getUserId(), pageable);

        // then
        assertThat(results).hasSize(1);
        
        Club club = (Club) results.getFirst()[0];
        assertThat(club.getName()).isEqualTo("부산 테니스 클럽");
        assertThat(club.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
    }

    @Test
//    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("키워드와 필터로 클럽을 검색할 수 있다")
    void searchByKeywordWithFilter() {
        // when - 축구 키워드로 검색
        List<Object[]> results = clubRepository.searchByKeywordWithFilter(
                "축구", null, null, null, "MEMBER_COUNT", pageable);

        // then
        assertThat(results).hasSize(1);
        
        Object[] result = results.getFirst();
        assertThat(result[1]).isEqualTo("서울 축구 클럽");
        assertThat(result[6]).isEqualTo(2L); // member_count
        
        // cleanup
        userClubRepository.deleteAll();
        clubRepository.deleteAll();
        userRepository.deleteAll();
        interestRepository.deleteAll();
    }

    @Test
    @DisplayName("함께하는 멤버들의 다른 모임을 조회할 수 있다")
    void findClubsByTeammates() {
        // given - testUser1과 같은 클럽(sportsClubInSeoul)에 속한 testUser2가 있음
        // testUser2가 다른 클럽에도 가입하도록 설정
        UserClub userClub = UserClub.builder()
                .user(testUser2)
                .club(cultureClubInSeoul)
                .clubRole(ClubRole.MEMBER)
                .build();
        userClubRepository.save(userClub);

        // when - testUser1 기준으로 팀메이트들의 다른 모임 조회
        List<Object[]> results = clubRepository.findClubsByTeammates(testUser1.getUserId(), pageable);

        // then
        assertThat(results).hasSize(1);
        
        Club club = (Club) results.getFirst()[0];
        assertThat(club.getName()).isEqualTo("서울 독서 모임");
    }

    @Test
    @Transactional
    @DisplayName("클럽 ID로 클럽을 조회할 수 있다")
    void findByClubId() {
        // when
        Club foundClub = clubRepository.findByClubId(exerciseClubInSeoul.getClubId());

        // then
        assertThat(foundClub).isNotNull();
        assertThat(foundClub.getName()).isEqualTo("서울 축구 클럽");
        assertThat(foundClub.getDescription()).isEqualTo("서울에서 함께 축구해요!");
        assertThat(foundClub.getUserLimit()).isEqualTo(20);
        assertThat(foundClub.getCity()).isEqualTo("서울");
        assertThat(foundClub.getDistrict()).isEqualTo("강남구");
        assertThat(foundClub.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
    }

    @Test
    @DisplayName("존재하지 않는 클럽 ID로 조회시 null을 반환한다")
    void findByClubId_NotFound() {
        // when
        Club foundClub = clubRepository.findByClubId(999L);

        // then
        assertThat(foundClub).isNull();
    }
}