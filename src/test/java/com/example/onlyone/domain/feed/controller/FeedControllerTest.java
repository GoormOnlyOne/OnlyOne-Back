package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.club.controller.ClubController;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.service.FeedService;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(controllers = FeedController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 전부 비활성
@Import(GlobalExceptionHandler.class)     // 전역 예외핸들러 등록
class FeedControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private FeedService feedService; // 컨트롤러 의존 서비스만 목
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean
    private UserRepository userRepository;

    private String toJson(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private static List<String> urls(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "img" + (i + 1) + ".jpg")
                .toList();
    }

    @DisplayName("이미지가 1개 미만이면 400을 반환한다")
    @Test
    void createFeed_fail_whenImagesLessThanOne() throws Exception {
        Long clubId = 1L;
        FeedRequestDto req = FeedRequestDto.builder()
                .feedUrls(List.of()) // 0개
                .content("설명")
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.feedUrls").value("이미지는 최소 1개 이상 최대 5개까지입니다."));
    }

    @DisplayName("이미지가 5개 초과이면 400을 반환한다")
    @Test
    void createFeed_fail_whenImagesMoreThanFive() throws Exception {
        Long clubId = 1L;
        FeedRequestDto req = FeedRequestDto.builder()
                .feedUrls(List.of("1.jpg","2.jpg","3.jpg","4.jpg","5.jpg","6.jpg")) // 6개
                .content("설명")
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.feedUrls")
                        .value("이미지는 최소 1개 이상 최대 5개까지입니다."));
    }

    @DisplayName("이미지 목록이 null이면 400을 반환한다")
    @Test
    void createFeed_fail_whenImagesNull() throws Exception {
        Long clubId = 1L;
        // feedUrls = null
        FeedRequestDto req = FeedRequestDto.builder()
                .feedUrls(null)
                .content("설명")
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                // @NotNull에 커스텀 메시지를 안 달았다면 기본 메시지라 환경마다 달라질 수 있으니 존재만 확인
                .andExpect(jsonPath("$.data.validation.feedUrls").exists());
    }


    @DisplayName("설명이 50자 초과이면 400을 반환한다")
    @Test
    void createFeed_fail_whenContentExceeds50() throws Exception {
        Long clubId = 1L;
        String over50 = "가".repeat(51);

        FeedRequestDto req = FeedRequestDto.builder()
                .feedUrls(List.of("a.jpg")) // 유효
                .content(over50)            // 51자
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content")
                        .value("피드 설명은 50자 이내여야 합니다."));
    }


    @Test
    @DisplayName("좋아요가 없으면 생성되고 200 OK를 반환한다")
    void toggleLike_create_returns200() throws Exception {
        Long clubId = 1L, feedId = 10L;
        when(feedService.toggleLike(clubId, feedId)).thenReturn(true);

        mockMvc.perform(put("/clubs/{clubId}/feeds/{feedId}/likes", clubId, feedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(feedService, times(1)).toggleLike(clubId, feedId);
    }

    @Test
    @DisplayName("이미 좋아요 상태면 취소되고 204 No Content를 반환한다")
    void toggleLike_cancel_returns204() throws Exception {
        Long clubId = 1L, feedId = 10L;
        when(feedService.toggleLike(clubId, feedId)).thenReturn(false);

        mockMvc.perform(put("/clubs/{clubId}/feeds/{feedId}/likes", clubId, feedId))
                .andExpect(status().isNoContent())
                .andExpect(content().string("")); // 본문 없음

        verify(feedService, times(1)).toggleLike(clubId, feedId);
    }

    @DisplayName("댓글 내용이 공백/빈 문자열이면 400 반환")
    @Test
    void createComment_fail_whenBlank() throws Exception {
        Long clubId = 1L, feedId = 10L;

        FeedCommentRequestDto req = FeedCommentRequestDto.builder()
                .content("   ") // 공백만
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds/{feedId}/comments", clubId, feedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content").exists());

        verifyNoInteractions(feedService); // 검증 실패이므로 서비스 호출 없어야 함
    }

    @DisplayName("댓글 내용이 50자 초과면 400 반환")
    @Test
    void createComment_fail_whenTooLong() throws Exception {
        Long clubId = 1L, feedId = 10L;

        FeedCommentRequestDto req = FeedCommentRequestDto.builder()
                .content("A".repeat(51)) // 51자
                .build();

        mockMvc.perform(post("/clubs/{clubId}/feeds/{feedId}/comments", clubId, feedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.content").value("댓글은 50자 이내여야 합니다."));

        verifyNoInteractions(feedService);
    }
}
