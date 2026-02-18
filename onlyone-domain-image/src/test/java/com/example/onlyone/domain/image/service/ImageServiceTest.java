package com.example.onlyone.domain.image.service;

import java.net.URL;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.example.onlyone.domain.image.dto.request.PresignedUrlRequestDto;
import com.example.onlyone.domain.image.entity.ImageFolderType;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageService 단위 테스트")
class ImageServiceTest {

    @InjectMocks ImageService imageService;
    @Mock S3Presigner s3Presigner;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucketName", "bucket");
        ReflectionTestUtils.setField(imageService, "cloudfrontDomain", "cdn.example.com");
    }

    private void stubPresigner(String url) throws Exception {
        PresignedPutObjectRequest pre = mock(PresignedPutObjectRequest.class);
        given(pre.url()).willReturn(new URL(url));
        given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(pre);
    }

    // =========================================================================
    @Nested
    @DisplayName("Presigned URL 생성")
    class GeneratePresignedUrl {

        @Test
        @DisplayName("성공: PNG 이미지")
        void successPng() throws Exception {
            stubPresigner("https://s3.amazonaws.com/bucket/chat/xxx.png");

            var result = imageService.generatePresignedUrl(
                    "CHAT", new PresignedUrlRequestDto("origin.png", "image/png", 1024L));

            assertThat(result.presignedUrl()).isNotBlank();
            assertThat(result.imageUrl()).startsWith("https://cdn.example.com/chat/").endsWith(".png");
        }

        @Test
        @DisplayName("성공: JPEG 이미지")
        void successJpeg() throws Exception {
            stubPresigner("https://s3.amazonaws.com/bucket/feed/yyy.jpeg");

            var result = imageService.generatePresignedUrl(
                    "FEED", new PresignedUrlRequestDto("photo.jpeg", "image/jpeg", 2048L));

            assertThat(result.presignedUrl()).isNotBlank();
            assertThat(result.imageUrl()).startsWith("https://cdn.example.com/feed/").endsWith(".jpeg");
        }

        @Test
        @DisplayName("성공: 정확히 5MB (경계값)")
        void successExactly5MB() throws Exception {
            long exactly5MB = 5L * 1024 * 1024;
            stubPresigner("https://s3.amazonaws.com/bucket/user/zzz.png");

            var result = imageService.generatePresignedUrl(
                    "USER", new PresignedUrlRequestDto("big.png", "image/png", exactly5MB));

            assertThat(result.presignedUrl()).isNotBlank();
            assertThat(result.imageUrl()).startsWith("https://cdn.example.com/user/");
        }

        @Test
        @DisplayName("실패: GIF 컨텐츠 타입 → INVALID_IMAGE_CONTENT_TYPE")
        void failInvalidContentType() {
            assertThatThrownBy(() ->
                    imageService.generatePresignedUrl("CHAT",
                            new PresignedUrlRequestDto("a.gif", "image/gif", 100L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }

        @Test
        @DisplayName("실패: 5MB 초과 → IMAGE_SIZE_EXCEEDED")
        void failSizeExceeded() {
            long over = 5L * 1024 * 1024 + 1;

            assertThatThrownBy(() ->
                    imageService.generatePresignedUrl("CHAT",
                            new PresignedUrlRequestDto("a.jpg", "image/jpeg", over)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }

        @Test
        @DisplayName("실패: 크기 0바이트 → INVALID_IMAGE_SIZE")
        void failSizeZero() {
            assertThatThrownBy(() ->
                    imageService.generatePresignedUrl("CHAT",
                            new PresignedUrlRequestDto("a.png", "image/png", 0L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_IMAGE_SIZE);
        }

        @Test
        @DisplayName("실패: 잘못된 폴더 타입 → INVALID_IMAGE_FOLDER_TYPE")
        void failInvalidFolderType() {
            assertThatThrownBy(() ->
                    imageService.generatePresignedUrl("UNKNOWN",
                            new PresignedUrlRequestDto("a.png", "image/png", 100L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_IMAGE_FOLDER_TYPE);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("ImageFolderType 변환")
    class FolderTypeConversion {

        @Test
        @DisplayName("성공: 대문자 CHAT → CHAT")
        void fromUpperCase() {
            assertThat(ImageFolderType.from("CHAT")).isEqualTo(ImageFolderType.CHAT);
        }

        @Test
        @DisplayName("성공: 소문자 chat → CHAT (대소문자 무시)")
        void fromLowerCase() {
            assertThat(ImageFolderType.from("chat")).isEqualTo(ImageFolderType.CHAT);
        }

        @Test
        @DisplayName("성공: 모든 폴더 타입 변환")
        void allTypesResolvable() {
            assertThat(ImageFolderType.from("USER")).isEqualTo(ImageFolderType.USER);
            assertThat(ImageFolderType.from("FEED")).isEqualTo(ImageFolderType.FEED);
            assertThat(ImageFolderType.from("CLUB")).isEqualTo(ImageFolderType.CLUB);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 타입 → INVALID_IMAGE_FOLDER_TYPE")
        void failUnknownType() {
            assertThatThrownBy(() -> ImageFolderType.from("VIDEO"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_IMAGE_FOLDER_TYPE);
        }
    }
}
