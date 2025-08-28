package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;


import java.util.Locale;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = ClubController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        },
        excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
            })
@DisplayNameGeneration(org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores.class)
public class ClubControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ClubService clubService;
    @MockBean(JpaMetamodelMappingContext.class)
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void 모임이_정상적으로_생성된다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 코드 시작합시다",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );

        // when & then
        mockMvc.perform(post("/clubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated());
    }

    @Test
    void 모임명이_20자를_초과하면_입력값_예외가_발생한다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "온리원 첫 번째 모임 온리원 첫 번째 모임 온리원 첫 번째 모임 온리원 첫 번째 모임 온리원 첫 번째 모임 온리원 첫 번째 모임 ",
                10,
                "테스트 코드 시작합시다",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = new ClubCreateResponseDto(1L);
        when(clubService.createClub(requestDto)).thenReturn(responseDto);

        // when & then
        mockMvc.perform(post("/clubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.name")
                        .value("모임명은 20자 이내여야 합니다."));
    }

    @Test
    void 모임_설명이_50자를_초과하면_입력값_예외가_발생한다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드테스트 코드 시작합시다  시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다 테스트 코드 시작합시다테스트 코드 시작합시다테스트 코드 시작합시다테스트 코드 시작합시다테스트 코드 시작합시다",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = new ClubCreateResponseDto(1L);
        when(clubService.createClub(requestDto)).thenReturn(responseDto);

        // when & then
        mockMvc.perform(post("/clubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.description")
                        .value("모임 설명은 50자 이내여야 합니다."));
    }

    @Test
    void 모임_정원이_1명_미만이면__입력값_예외가_발생한다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                -10,
                "테스트 코드 시작합시다",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = new ClubCreateResponseDto(1L);
        when(clubService.createClub(requestDto)).thenReturn(responseDto);

        // when & then
        mockMvc.perform(post("/clubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정원은 1명 이상이어야 합니다."));
    }

    @Test
    void 모임_정원이_100명_초과면__입력값_예외가_발생한다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                110,
                "테스트 코드 시작합시다",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = new ClubCreateResponseDto(1L);
        when(clubService.createClub(requestDto)).thenReturn(responseDto);

        // when & then
        mockMvc.perform(post("/clubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정원은 100명 이하여야 합니다."));
    }

    @Test
    void NotBlank_제약조건_예외가_발생한다() throws Exception {
        // given
        ClubRequestDto requestDto = new ClubRequestDto(
                "",
                10,
                "",
                null,
                "",
                "",
                ""
        );
        ClubCreateResponseDto responseDto = new ClubCreateResponseDto(1L);
        when(clubService.createClub(requestDto)).thenReturn(responseDto);

        // when & then
        mockMvc.perform(post("/clubs")
                        .locale(Locale.KOREAN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.name")
                        .value("공백일 수 없습니다"))
                .andExpect(jsonPath("$.data.validation.description")
                        .value("공백일 수 없습니다"))
                .andExpect(jsonPath("$.data.validation.city")
                        .value("공백일 수 없습니다"))
                .andExpect(jsonPath("$.data.validation.district")
                        .value("공백일 수 없습니다"))
                .andExpect(jsonPath("$.data.validation.category")
                        .value("공백일 수 없습니다"))
        ;

    }


}
