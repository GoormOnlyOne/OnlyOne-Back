package com.example.onlyone.domain.image.controller;

import com.example.onlyone.domain.image.dto.request.PresignedUrlRequestDto;
import com.example.onlyone.domain.image.dto.response.PresignedUrlResponseDto;
import com.example.onlyone.domain.image.service.ImageService;
import com.example.onlyone.domain.image.exception.ImageErrorCode;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ImageController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ImageController 슬라이스 테스트")
class ImageControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ImageService imageService;
    @MockitoBean JpaMetamodelMappingContext jpaMetamodelMappingContext;

    // =========================================================================
    @Nested
    @DisplayName("Presigned URL 생성 API")
    class PresignedUrlApi {

        @Test
        @DisplayName("성공: 200 + presignedUrl + imageUrl 반환")
        void success() throws Exception {
            String body = """
              {"fileName": "origin.png", "contentType": "image/png", "imageSize": 1024}
            """;

            var resp = new PresignedUrlResponseDto(
                    "https://s3.amazonaws.com/bucket/chat/xxx.png?X-Amz-Signature=abc",
                    "https://cdn.example.com/chat/uuid.png"
            );

            given(imageService.generatePresignedUrl(eq("CHAT"), any(PresignedUrlRequestDto.class)))
                    .willReturn(resp);

            mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.presignedUrl").value(resp.presignedUrl()))
                    .andExpect(jsonPath("$.data.imageUrl").value(resp.imageUrl()));
        }

        @Test
        @DisplayName("실패: 서비스 예외 → CustomException 전파")
        void failServiceException() {
            String body = """
              {"fileName": "origin.jpg", "contentType": "image/jpeg", "imageSize": 2048}
            """;

            given(imageService.generatePresignedUrl(anyString(), any(PresignedUrlRequestDto.class)))
                    .willThrow(new CustomException(ImageErrorCode.IMAGE_UPLOAD_FAILED));

            assertThatThrownBy(() ->
                    mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
            ).hasCauseInstanceOf(CustomException.class);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("요청 검증 (@Valid)")
    class RequestValidation {

        @Test
        @DisplayName("실패: fileName 빈 문자열 → 400")
        void failBlankFileName() throws Exception {
            String body = """
              {"fileName": "", "contentType": "image/png", "imageSize": 1024}
            """;

            mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패: contentType 누락 → 400")
        void failMissingContentType() throws Exception {
            String body = """
              {"fileName": "photo.png", "imageSize": 1024}
            """;

            mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패: imageSize 5MB 초과 → 400")
        void failImageSizeExceeded() throws Exception {
            String body = """
              {"fileName": "photo.png", "contentType": "image/png", "imageSize": 5242881}
            """;

            mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패: imageSize 0 → 400")
        void failImageSizeZero() throws Exception {
            String body = """
              {"fileName": "photo.png", "contentType": "image/png", "imageSize": 0}
            """;

            mockMvc.perform(post("/api/v1/images/{imageFolderType}/presigned-url", "CHAT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }
}
