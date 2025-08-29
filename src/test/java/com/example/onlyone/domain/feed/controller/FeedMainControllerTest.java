package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.service.FeedMainService;
import com.example.onlyone.domain.feed.service.FeedService;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@ActiveProfiles("test")
@WebMvcTest(controllers = FeedMainController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class FeedMainControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FeedMainService feedMainService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean
    private UserRepository userRepository;

    private String toJson(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    @DisplayName("리피드: content가 null(누락)이면 400")
    void createRefeed_fail_whenContentNull() throws Exception {
        long feedId = 10L, clubId = 1L;

        mockMvc.perform(post("/feeds/{feedId}/{clubId}", feedId, clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")) // content 누락 -> null
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content").exists());

        verifyNoInteractions(feedMainService);
    }

    @Test
    @DisplayName("리피드: content가 공백이면 400")
    void createRefeed_fail_whenContentBlank() throws Exception {
        long feedId = 10L, clubId = 1L;
        RefeedRequestDto req = RefeedRequestDto.builder()
                .content("   ")
                .build();

        mockMvc.perform(post("/feeds/{feedId}/{clubId}", feedId, clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content").exists());

        verifyNoInteractions(feedMainService);
    }

    @Test
    @DisplayName("리피드: content가 50자 초과면 400")
    void createRefeed_fail_whenContentExceeds50() throws Exception {
        long feedId = 10L, clubId = 1L;
        RefeedRequestDto req = RefeedRequestDto.builder()
                .content("가".repeat(51))
                .build();

        mockMvc.perform(post("/feeds/{feedId}/{clubId}", feedId, clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content").value("피드 설명은 50자 이내여야 합니다."));

        verifyNoInteractions(feedMainService);
    }

    @Test
    @DisplayName("리피드: content가 1~50자면 201 Created")
    void createRefeed_success_whenContentValid() throws Exception {
        long feedId = 10L, clubId = 1L;
        RefeedRequestDto req = RefeedRequestDto.builder()
                .content("가".repeat(50)) // 경계값
                .build();

        doNothing().when(feedMainService)
                .createRefeed(anyLong(), anyLong(), any(RefeedRequestDto.class));

        mockMvc.perform(post("/feeds/{feedId}/{clubId}", feedId, clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(feedMainService, times(1))
                .createRefeed(eq(feedId), eq(clubId), any(RefeedRequestDto.class));
    }
}
