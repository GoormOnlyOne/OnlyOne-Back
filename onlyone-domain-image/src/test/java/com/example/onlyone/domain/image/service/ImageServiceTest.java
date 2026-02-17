package com.example.onlyone.domain.image.service;

import java.net.URL;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {
    @InjectMocks ImageService imageService;
    @Mock S3Presigner s3Presigner;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucketName", "bucket");
        ReflectionTestUtils.setField(imageService, "cloudfrontDomain", "cdn.example.com");
    }

    @Test
    @DisplayName("확장자_JPEG/PNG_크기_5MB_이하_이미지만_첨부_가능하다")
    void attachSuccess() throws Exception {
        // given
        PresignedPutObjectRequest pre1 = mock(PresignedPutObjectRequest.class); // 1st call
        PresignedPutObjectRequest pre2 = mock(PresignedPutObjectRequest.class); // 2nd call

        given(pre1.url()).willReturn(new URL("https://s3.amazonaws.com/bucket/chat/xxx.png"));
        given(pre2.url()).willReturn(new URL("https://s3.amazonaws.com/bucket/chat/yyy.jpeg"));


        given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .willReturn(pre1, pre2);

        // when
        var png = imageService.generatePresignedUrlWithImageUrl(
                "CHAT", "origin.png", "image/png", 1024L);
        var jpeg = imageService.generatePresignedUrlWithImageUrl(
                "CHAT", "origin.jpeg", "image/jpeg", 1024L);

        // then
        assertThat(png.presignedUrl()).isNotBlank();
        assertThat(png.imageUrl()).startsWith("https://cdn.example.com/chat/").endsWith(".png");

        assertThat(jpeg.presignedUrl()).isNotBlank();
        assertThat(jpeg.imageUrl()).startsWith("https://cdn.example.com/chat/").endsWith(".jpeg");
    }

    @Test
    @DisplayName("허용되지_않은_타입의_이미지는_첨부가_불가능하다")
    void invalidImageType() {
        // given
        String folder = "CHAT";
        String fileName = "a.gif";
        String contentType = "image/gif";
        long size = 100L;

        // when & then
        assertThatThrownBy(() ->
                imageService.generatePresignedUrlWithImageUrl(folder, fileName, contentType, size))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
    }

    @Test
    @DisplayName("크기_5MB_초과의_이미지는_첨부가_불가능하다")
    void sizeExceededRejected() {
        // given
        String folder = "CHAT";
        String fileName = "a.jpg";
        String contentType = "image/jpeg";
        long over = 5L * 1024 * 1024 + 1;

        // when & then
        assertThatThrownBy(() ->
                imageService.generatePresignedUrlWithImageUrl(folder, fileName, contentType, over))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("이미지_외의_폴더타입은_첨부가_불가능하다")
    void invalidFileType() {
        // given
        String folder = "UNKNOWN";
        String fileName = "a.png";
        String contentType = "image/png";
        long size = 100L;

        // when & then
        assertThatThrownBy(() ->
                imageService.generatePresignedUrlWithImageUrl(folder, fileName, contentType, size))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_IMAGE_FOLDER_TYPE);
    }
}
