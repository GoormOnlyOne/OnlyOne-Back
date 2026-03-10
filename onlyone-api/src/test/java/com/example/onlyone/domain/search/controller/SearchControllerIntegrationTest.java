package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.search.dto.response.ClubResponseDto;
import com.example.onlyone.domain.search.service.SearchService;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(SearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser
public class SearchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private SearchService searchService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private ClubResponseDto createClub(Long id, String name, String interest, String district) {
        return new ClubResponseDto(id, name, name + " 설명", interest, district, 10L, "image.jpg", false);
    }

    @Test
    @DisplayName("사용자 맞춤 추천 - 사용자 조건에 맞는 모임이 우선 추천")
    void recommendedClubsPriority() throws Exception {
        given(searchService.recommendedClubs(anyInt(), anyInt())).willReturn(List.of(
                createClub(1L, "강남 운동 클럽 1", "운동", "강남구"),
                createClub(2L, "강남 문화 클럽 1", "문화", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].joined", everyItem(equalTo(false))))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))));
    }

    @Test
    @DisplayName("관심사 기반 검색")
    void searchClubByInterest() throws Exception {
        given(searchService.searchClubByInterest(eq(1L), anyInt())).willReturn(List.of(
                createClub(1L, "운동 클럽 1", "운동", "강남구"),
                createClub(2L, "운동 클럽 2", "운동", "서초구")
        ));

        mockMvc.perform(get("/api/v1/search/interests")
                        .param("interestId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].interest").value(everyItem(equalTo("운동"))));
    }

    @Test
    @DisplayName("관심사 기반 검색 - 존재하지 않는 관심사 ID로 검색")
    void searchClubByNoneExistInterest() throws Exception {
        given(searchService.searchClubByInterest(eq(99999999L), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/search/interests")
                        .param("interestId", "99999999")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("지역 기반 검색 - 서울 강남구 클럽 검색")
    void searchClubByLocationSeoulGangnam() throws Exception {
        given(searchService.searchClubByLocation(eq("서울"), eq("강남구"), anyInt())).willReturn(List.of(
                createClub(1L, "강남 클럽 1", "운동", "강남구"),
                createClub(2L, "강남 클럽 2", "문화", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search/locations")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].district", everyItem(equalTo("강남구"))));
    }

    @Test
    @DisplayName("지역 기반 검색 - 등록된 모임이 없는 지역")
    void searchClubByLocationWithNoClubs() throws Exception {
        given(searchService.searchClubByLocation(eq("제주도"), eq("제주시"), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/search/locations")
                        .param("city", "제주도")
                        .param("district", "제주시")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("통합 검색 - 키워드로만 검색")
    void searchClubsWithKeywordOnly() throws Exception {
        given(searchService.searchClubs(any())).willReturn(List.of(
                createClub(1L, "강남 운동 클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "강남")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    @DisplayName("통합 검색 - 지역 필터만 적용")
    void searchClubsWithLocationFilterOnly() throws Exception {
        given(searchService.searchClubs(any())).willReturn(List.of(
                createClub(1L, "강남 클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    @DisplayName("통합 검색 - 관심사 필터만 적용")
    void searchClubsWithInterestFilterOnly() throws Exception {
        given(searchService.searchClubs(any())).willReturn(List.of(
                createClub(1L, "운동 클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("interestId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].interest").value("운동"));
    }

    @Test
    @DisplayName("통합 검색 - 모든 필터 적용")
    void searchClubsWithAllFilters() throws Exception {
        given(searchService.searchClubs(any())).willReturn(List.of(
                createClub(1L, "강남 운동 클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "운동")
                        .param("city", "서울")
                        .param("district", "강남구")
                        .param("interestId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    @DisplayName("함께하는 멤버들의 다른 모임 - 정상 조회")
    void getClubsByTeammatesSuccess() throws Exception {
        given(searchService.getClubsByTeammates(anyInt(), anyInt())).willReturn(List.of(
                createClub(1L, "팀메이트 클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search/teammates-clubs")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ============예외 상황============

    @Test
    @DisplayName("관심사 기반 검색 - 관심사 null일 때 예외를 반환한다.")
    void searchClubsByInterestNull() throws Exception {
        mockMvc.perform(get("/api/v1/search/interests")
                        .param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관심사 기반 검색 - 빈 문자열 파라미터 (타입 변환 실패)")
    void searchClubsByInterestEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/search/interests")
                        .param("interestId", "")
                        .param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관심사 기반 검색 - 잘못된 형식의 파라미터 (타입 변환 실패)")
    void searchClubsByInterestMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/search/interests")
                        .param("interestId", "abc")
                        .param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("통합 검색 - 필터가 전부 null 일 때 전체 조회")
    void searchClubsFilterNull() throws Exception {
        given(searchService.searchClubs(any())).willReturn(List.of(
                createClub(1L, "클럽", "운동", "강남구")
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // 예외 처리 테스트는 SearchServiceTest (단위 테스트)에서 커버
    // HTTP 파라미터 검증은 SearchControllerTest에서 커버
}
