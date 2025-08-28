package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(controllers = ClubController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 전부 비활성
@Import(GlobalExceptionHandler.class)     // 전역 예외핸들러 등록
class ClubControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ClubService clubService; // 컨트롤러 의존 서비스만 목
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private ClubRequestDto base() {
        return ClubRequestDto.builder()
                .name("서울 축구 클럽")
                .userLimit(20)
                .description("서울에서 함께 축구해요!")
                .clubImage("soccer.jpg")
                .city("서울")
                .district("강남구")
                .category("EXERCISE")
                .build();
    }

    @Test
    @DisplayName("모임명 20자 초과 시 400 반환")
    void update_nameTooLong_returns400() throws Exception {
        Long clubId = 1L;

        ClubRequestDto invalid = base().toBuilder()
                .name("A".repeat(21)) // @Size(max = 20) 위반
                .build();

        mockMvc.perform(patch("/clubs/{clubId}", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.name")
                        .value("모임명은 20자 이내여야 합니다."));
    }
}