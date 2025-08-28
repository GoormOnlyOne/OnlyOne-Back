package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.search.service.SearchService;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(SearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SearchControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SearchService searchService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    // 헬퍼 메서드: 테스트 데이터 생성 중복 제거
    private ClubResponseDto createClub(Long clubId, String name, String description, String interest, String district, Long memberCount, boolean isJoined) {
        return ClubResponseDto.builder()
                .clubId(clubId)
                .name(name)
                .description(description)
                .interest(interest)
                .district(district)
                .memberCount(memberCount)
                .image("image.jpg")
                .isJoined(isJoined)
                .build();
    }

    private ClubResponseDto createClub(Long clubId, String name, String interest, String district, Long memberCount) {
        return createClub(clubId, name, name + " 설명", interest, district, memberCount, false);
    }

    @Test
    @DisplayName("사용자 맞춤 추천 - 성공")
    void recommendClubsSuccess() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(1L, "테스트 클럽1", "테스트 설명1", "운동", "강남구", 10L, false)
        );

        given(searchService.recommendedClubs(0, 20)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/recommendations")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].clubId").value(1))
                .andExpect(jsonPath("$.data[0].name").value("테스트 클럽1"))
                .andExpect(jsonPath("$.data[0].description").value("테스트 설명1"))
                .andExpect(jsonPath("$.data[0].interest").value("운동"))
                .andExpect(jsonPath("$.data[0].district").value("강남구"))
                .andExpect(jsonPath("$.data[0].memberCount").value(10))
                .andExpect(jsonPath("$.data[0].image").value("image.jpg"))
                .andExpect(jsonPath("$.data[0].joined").value(false));
    }

    @Test
    @DisplayName("관심사 기반 모임 검색 - 성공")
    void searchClubByInterestSuccess() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(2L, "운동 클럽", "운동하는 모임", "운동", "강남구", 10L, false)
        );

        given(searchService.searchClubByInterest(1L, 0)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].clubId").value(2))
                .andExpect(jsonPath("$.data[0].name").value("운동 클럽"))
                .andExpect(jsonPath("$.data[0].description").value("운동하는 모임"))
                .andExpect(jsonPath("$.data[0].interest").value("운동"))
                .andExpect(jsonPath("$.data[0].district").value("강남구"))
                .andExpect(jsonPath("$.data[0].memberCount").value(10))
                .andExpect(jsonPath("$.data[0].image").value("image.jpg"))
                .andExpect(jsonPath("$.data[0].joined").value(false));

    }

    @Test
    @DisplayName("지역 기반 모임 검색 - 성공")
    void searchClubByLocationSuccess() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(3L, "강남구 클럽", "강남구 모임", "운동", "강남구", 10L, false)
        );

        given(searchService.searchClubByLocation("서울", "강남구", 0)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/locations")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].clubId").value(3))
                .andExpect(jsonPath("$.data[0].name").value("강남구 클럽"))
                .andExpect(jsonPath("$.data[0].district").value("강남구"))
                .andExpect(jsonPath("$.data[0].memberCount").value(10))
                .andExpect(jsonPath("$.data[0].joined").value(false));

    }

    @Test
    @DisplayName("모임 통합 검색 - 키워드만")
    void searchClubs_WithKeywordOnly() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(4L, "테스트 클럽", "테스트용 클럽", "기타", "서초구", 5L, false)
        );
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("keyword", "테스트")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("테스트 클럽"));
    }

    @Test
    @DisplayName("모임 통합 검색 - 지역 필터만")
    void searchClubs_WithLocationOnly() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(5L, "강남 클럽", "강남 지역 클럽", "문화", "강남구", 8L, false)
        );
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("city", "서울특별시")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].district").value("강남구"));
    }

    @Test
    @DisplayName("모임 통합 검색 - 관심사 필터만")
    void searchClubs_WithInterestOnly() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(6L, "운동 클럽", "운동 전용 클럽", "운동", "서초구", 12L, false)
        );
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("interestId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].interest").value("운동"));
    }

    @Test
    @DisplayName("모임 통합 검색 - 모든 필터")
    void searchClubs_WithAllFilters() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(7L, "완벽한 클럽", "모든 조건을 만족하는 클럽", "운동", "강남구", 25L, false)
        );
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("keyword", "완벽")
                        .param("city", "서울특별시")
                        .param("district", "강남구")
                        .param("interestId", "1")
                        .param("sortBy", "LATEST")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("완벽한 클럽"));
    }

    @Test
    @DisplayName("모임 통합 검색 - 정렬 옵션 MEMBER_COUNT")
    void searchClubs_SortByMemberCount() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("sortBy", "MEMBER_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("모임 통합 검색 - 정렬 옵션 LATEST")
    void searchClubs_SortByLatest() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("sortBy", "LATEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("모임 통합 검색 - 기본값 테스트")
    void searchClubs_DefaultValues() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.searchClubs(any())).willReturn(clubs);

        // when & then (파라미터 없이 호출)
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("모임 통합 검색 - 빈 결과")
    void searchClubs_EmptyResult() throws Exception {
        // given
        List<ClubResponseDto> emptyClubs = List.of();
        given(searchService.searchClubs(any())).willReturn(emptyClubs);

        // when & then
        mockMvc.perform(get("/search")
                        .param("keyword", "존재하지않는클럽"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("팀메이트들의 다른 모임 조회 - 성공")
    void getClubsByTeammates_Success() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of(
                createClub(8L, "팀메이트 클럽", "사교", "서초구", 12L),
                createClub(9L, "함께하는 클럽", "문화", "강남구", 8L)
        );
        given(searchService.getClubsByTeammates(0, 20)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/teammates-clubs")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("팀메이트 클럽"))
                .andExpect(jsonPath("$.data[0].interest").value("사교"))
                .andExpect(jsonPath("$.data[1].name").value("함께하는 클럽"))
                .andExpect(jsonPath("$.data[1].interest").value("문화"));
    }

    @Test
    @DisplayName("팀메이트 모임 조회 - 기본값 테스트")
    void getClubsByTeammates_DefaultValues() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.getClubsByTeammates(0, 20)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/teammates-clubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("팀메이트 모임 조회 - 빈 결과")
    void getClubsByTeammates_EmptyResult() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.getClubsByTeammates(1, 10)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/teammates-clubs")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("사용자 맞춤 추천 - 기본값 테스트")
    void recommendedClubs_DefaultValues() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.recommendedClubs(0, 20)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("관심사 검색 - 기본 페이지 값 테스트")
    void searchClubByInterest_DefaultPage() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.searchClubByInterest(1L, 0)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/interests")
                        .param("interestId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("지역 검색 - 기본 페이지 값 테스트")
    void searchClubByLocation_DefaultPage() throws Exception {
        // given
        List<ClubResponseDto> clubs = List.of();
        given(searchService.searchClubByLocation("서울", "강남구", 0)).willReturn(clubs);

        // when & then
        mockMvc.perform(get("/search/locations")
                        .param("city", "서울")
                        .param("district", "강남구"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}