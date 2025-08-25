package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
public class SearchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterestRepository interestRepository;
    @Autowired
    private UserClubRepository userClubRepository;
    @Autowired
    private UserInterestRepository userInterestRepository;

    // 인증이 필요하다면
    @MockitoBean
    private UserService userService; // 현재 사용자만 Mock

    private User testUser;

    @BeforeEach
    void setUp() {
        // 1. 관심사들 생성
        Interest exerciseInterest = interestRepository.save(Interest.builder()
                .category(Category.EXERCISE).build());
        Interest cultureInterest = interestRepository.save(Interest.builder()
                .category(Category.CULTURE).build());
        Interest musicInterest = interestRepository.save(Interest.builder()
                .category(Category.MUSIC).build());

        // 2. 테스트 사용자 생성 (서울 강남구, 운동+문화 관심사)
        testUser = userRepository.save(User.builder()
                .kakaoId(1L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("강남구")
                .build());

        // 3. 사용자 관심사 설정
        userInterestRepository.saveAll(List.of(
                UserInterest.builder().user(testUser).interest(exerciseInterest).build(),
                UserInterest.builder().user(testUser).interest(cultureInterest).build()
        ));

        // 4. 다양한 클럽들 생성 (추천 로직 테스트용)
        // 서울 강남구 운동 클럽들 (사용자 조건에 맞음)
        for (int i = 1; i <= 5; i++) {
            clubRepository.save(Club.builder()
                    .name("강남 운동 클럽 " + i)
                    .description("강남구 운동 클럽")
                    .city("서울")
                    .district("강남구")
                    .interest(exerciseInterest)
                    .userLimit(20)
                    .clubImage("exercise" + i + ".jpg")
                    .build());
        }

        // 서울 강남구 문화 클럽들 (사용자 조건에 맞음)
        for (int i = 1; i <= 3; i++) {
            clubRepository.save(Club.builder()
                    .name("강남 문화 클럽 " + i)
                    .description("강남구 문화 클럽")
                    .city("서울")
                    .district("강남구")
                    .interest(cultureInterest)
                    .userLimit(20)
                    .clubImage("culture" + i + ".jpg")
                    .build());
        }

        // 서울 서초구 운동 클럽들 (지역 다름)
        for (int i = 1; i <= 3; i++) {
            clubRepository.save(Club.builder()
                    .name("서초 운동 클럽 " + i)
                    .description("서초구 운동 클럽")
                    .city("서울")
                    .district("서초구")
                    .interest(exerciseInterest)
                    .userLimit(20)
                    .clubImage("seocho" + i + ".jpg")
                    .build());
        }

        // 부산 음악 클럽들 (지역+관심사 모두 다름)
        clubRepository.save(Club.builder()
                .name("부산 음악 클럽")
                .description("부산 음악 클럽")
                .city("부산")
                .district("해운대구")
                .interest(musicInterest)
                .userLimit(20)
                .clubImage("busan_music.jpg")
                .build());

        // 5. 사용자가 이미 가입한 클럽 (추천에서 제외되어야 함)
        Club joinedClub = clubRepository.save(Club.builder()
                .name("가입한 클럽")
                .description("이미 가입한 클럽")
                .city("서울")
                .district("강남구")
                .interest(exerciseInterest)
                .userLimit(20)
                .clubImage("joined.jpg")
                .build());

        userClubRepository.save(UserClub.builder()
                .user(testUser)
                .club(joinedClub)
                .clubRole(ClubRole.MEMBER)
                .build());

        // 6. 인증 Mock 설정
        given(userService.getCurrentUser()).willReturn(testUser);
    }

    @AfterEach
    void tearDown() {
        userClubRepository.deleteAll();
        userInterestRepository.deleteAll();
        clubRepository.deleteAll();
        userRepository.deleteAll();
        interestRepository.deleteAll();
    }

    @Test
    @DisplayName("사용자 맞춤 추천 - 사용자 조건에 맞는 모임이 우선 추천")
    void recommendedClubsPriority() throws Exception {
        // when & then
        mockMvc.perform(get("/search/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                // 강남구 + 운동/문화 클럽들이 나와야 함
                .andExpect(jsonPath("$.data[*].joined", everyItem(equalTo(false))))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))))
                .andExpect(jsonPath("$.data[*].interest", everyItem(anyOf(equalTo("운동"), equalTo("문화")))));
    }

    @Test
    @DisplayName("가입한 클럽은 추천에서 제외된다.")
    void recommendedClubsExcludeJoined() throws Exception {
        // when & then
        mockMvc.perform(get("/search/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                // 가입한 클럽이 결과에 포함되어 있지 않는지 확인
                .andExpect(jsonPath("$.data[*].joined").value(everyItem(equalTo(false))));

    }

    @Test
    @DisplayName("관심사 기반 검색")
    void searchClubByInterest() throws Exception {
        // given
        Interest exerciseInterest = interestRepository.findByCategory(Category.EXERCISE).orElseThrow();

        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", exerciseInterest.getInterestId().toString())
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[*].interest").value(everyItem(equalTo("운동"))));

    }

    @Test
    @DisplayName("관심사 기반 검색 - 존재하지 않는 관심사 ID로 검색")
    void searchClubByNoneExistInterest() throws Exception {
        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", "99999999")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));

    }

    @Test
    @DisplayName("지역 기반 검색 - 서울 강남구 클럽 검색")
    void searchClubByLocationSeoulGangnam() throws Exception {
        // when & then
        mockMvc.perform(get("/search/locations")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))));

    }

    @Test
    @DisplayName("지역 기반 검색 - 등록된 모임이 없는 지역")
    void searchClubByLocationWithNoClubs() throws Exception {
        // when & then
        mockMvc.perform(get("/search/locations")
                        .param("city", "제주도")
                        .param("district", "제주시")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));

    }

    @Test
    @DisplayName("통합 검색 - 키워드로만 검색")
    void searchClubsWithKeywordOnly() throws Exception {
        // when & then
        mockMvc.perform(get("/search")
                        .param("keyword", "강남")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[?(@.name =~ /.*강남.*/ || @.description =~ /.*강남.*/)]",
                        hasSize(greaterThan(0))));

    }

    @Test
    @DisplayName("통합 검색 - 지역 필터만 적용")
    void searchClubsWithLocationFilterOnly() throws Exception {
        // when & then
        mockMvc.perform(get("/search")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))));
    }

    @Test
    @DisplayName("통합 검색 - 관심사 필터만 적용")
    void searchClubsWithInterestFilterOnly() throws Exception {
        // given
        Interest exerciseInterest = interestRepository.findByCategory(Category.EXERCISE).orElseThrow();

        // when & then
        mockMvc.perform(get("/search")
                        .param("interestId", exerciseInterest.getInterestId().toString())
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[*].interest", everyItem(equalTo("운동"))));
    }

    @Test
    @DisplayName("통합 검색 - 관심사 필터만 적용")
    void searchClubsWithAllFilters() throws Exception {
        // given
        Interest exerciseInterest = interestRepository.findByCategory(Category.EXERCISE).orElseThrow();

        // when & then
        mockMvc.perform(get("/search")
                        .param("keyword", "운동")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("interestId", exerciseInterest.getInterestId().toString())
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                // 키워드, 지역, 관심사 모든 조건에 맞아야 함
                .andExpect(jsonPath("$.data[?(@.name =~ /.*운동.*/ || @.description =~ /.*운동.*/)]",
                        hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[*].interest", everyItem(equalTo("운동"))))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))));
    }

    @Test
    @DisplayName("함께하는 멤버들의 다른 모임 - 정상 조회")
    void getClubsByTeammatesSuccess() throws Exception {
        mockMvc.perform(get("/search/teammates-clubs")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[*].joined", everyItem(equalTo(false))));
    }




    // ============예외 상황============

    @Test
    @DisplayName("관심사 기반 검색 - 관심사 null일 때 예외를 반환한다.")
    void searchClubsByInterestNull() throws Exception {
        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("관심사 기반 검색 - 빈 문자열 파라미터 (타입 변환 실패)")
    void searchClubsByInterestEmpty() throws Exception {
        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", "")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("관심사 기반 검색 - 잘못된 형식의 파라미터 (타입 변환 실패)")
    void searchClubsByInterestMismatch() throws Exception {
        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", "abc")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("통합 검색 - 필터가 전부 null 일 때 전체 조회")
    void searchClubsFilterNull() throws Exception {
        mockMvc.perform(get("/search")
                        .param("page", "0"))
                // keyword, city, district, interestId 모두 누락 (모두 required=false)
                .andExpect(status().isOk())  // 정상 처리되어야 함
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("지역 기반 검색 - 빈 문자열로 서비스 로직 도달 후 CustomException")
    void searchClubsByLocationEmpty() throws Exception {
        mockMvc.perform(get("/search/locations")
                        .param("city", "")     // 빈 문자열
                        .param("district", "") // 빈 문자열
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_LOCATION"))  // 서비스의 CustomException
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("지역 기반 검색 - city 파라미터만 누락")
    void searchClubsByLocationMissingCity() throws Exception {
        mockMvc.perform(get("/search/locations")
                        .param("district", "강남구")
                        .param("page", "0"))
                // city 파라미터 누락
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("지역 기반 검색 - district 파라미터만 누락")
    void searchClubsByLocationMissingDistrict() throws Exception {
        mockMvc.perform(get("/search/locations")
                        .param("city", "서울")
                        .param("page", "0"))
                // city 파라미터 누락
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("통합 검색 - 1글자 키워드로 CustomException")
    void searchClubsShortKeyword() throws Exception {
        mockMvc.perform(get("/search")
                        .param("keyword", "운")  // 1글자
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("SEARCH_KEYWORD_TOO_SHORT"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("통합 검색 - city만 제공된 경우 CustomException")
    void searchClubsCityOnly() throws Exception {
        mockMvc.perform(get("/search")
                        .param("city", "서울")
                        // district 누락
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_SEARCH_FILTER"))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    @DisplayName("통합 검색 - district만 제공된 경우 CustomException")
    void searchClubsDistrictOnly() throws Exception {
        mockMvc.perform(get("/search")
                        .param("district", "강남구")
                        // city 누락
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_SEARCH_FILTER"))
                .andExpect(jsonPath("$.data.message").exists());
    }


}
