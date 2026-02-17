package com.example.onlyone.domain.image.controller;

import com.example.onlyone.domain.image.dto.response.PresignedUrlResponseDto;
import com.example.onlyone.domain.image.service.ImageService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ImageController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ImageControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ImageService imageService;

    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("Presigned_URL_업로드_성공_시_CloudFront_imageUrl을_반환한다")
    void presign_success_returns_upload_and_cloudfront_view_url() throws Exception {
        // given
        String body = """
          {
            "fileName": "origin.png",
            "contentType": "image/png",
            "imageSize": 1024
          }
        """;

        var resp = new PresignedUrlResponseDto(
                "https://s3.amazonaws.com/bucket/chat/xxx.png?X-Amz-Signature=abc",
                "https://cdn.example.com/chat/uuid.png"
        );

        given(imageService.generatePresignedUrlWithImageUrl(
                eq("CHAT"), eq("origin.png"), eq("image/png"), eq(1024L)
        )).willReturn(resp);

        // when & then
        mockMvc.perform(post("/api/v1/{imageFolderType}/presigned-url", "CHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.presignedUrl").value(resp.presignedUrl()))
                .andExpect(jsonPath("$.data.imageUrl").value(resp.imageUrl()));
    }

    @Test
    @DisplayName("Presigned_URL_업로드_실패_시_에러코드를_반환한다")
    void presign_failure_returns_server_error_and_error_code() throws Exception {
        // given
        String body = """
          {
            "fileName": "origin.jpg",
            "contentType": "image/jpeg",
            "imageSize": 2048
          }
        """;

        given(imageService.generatePresignedUrlWithImageUrl(anyString(), anyString(), anyString(), anyLong()))
                .willThrow(new CustomException(ErrorCode.IMAGE_UPLOAD_FAILED));

        // when & then
        mockMvc.perform(post("/api/v1/{imageFolderType}/presigned-url", "CHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("IMAGE_UPLOAD_FAILED"));
    }
}