package com.example.onlyone.domain.image.service;

import com.example.onlyone.domain.image.entity.ImageFolderType;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.cloudfront.domain}")
    private String cloudfrontDomain;

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB

    public String generatePresignedUrl(String imageFolderTypeStr, String originalFileName, String contentType, Long imageSize) {
        // 이미지 타입 검증
        ImageFolderType imageFolderType = validateImageFolderType(imageFolderTypeStr);

        // 컨텐츠 타입 검증
        validateImageContentType(contentType);

        // 이미지 크기 검증
        validateImageSize(imageSize);

        String fileName = generateFileName(originalFileName);
        String key = imageFolderType.getFolder() + "/" + fileName;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(putObjectRequest)
                .build();

        try {
            PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
            log.info("Generated presigned URL for file: {}", key);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for file: {}", key, e);
            throw new CustomException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    public String getImageUrl(ImageFolderType imageFolderType, String fileName) {
        return String.format("https://%s/%s/%s",
                cloudfrontDomain, imageFolderType.getFolder(), fileName);
    }

    public String extractFileNameFromPresignedUrl(String presignedUrl) {
        String[] parts = presignedUrl.split("\\?")[0].split("/");
        return parts[parts.length - 1];
    }

    private String generateFileName(String originalFileName) {
        String extension = getFileExtension(originalFileName);
        return UUID.randomUUID().toString() + extension;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    public ImageFolderType validateImageFolderType(String imageFolderTypeStr) {
        ImageFolderType imageFolderType = ImageFolderType.fromString(imageFolderTypeStr);
        if (imageFolderType == null) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_FOLDER_TYPE);
        }
        return imageFolderType;
    }

    private void validateImageContentType(String contentType) {
        if (!isValidImageContentType(contentType)) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }
    }

    private boolean isValidImageContentType(String contentType) {
        return contentType != null && (
                contentType.equals("image/jpeg") || contentType.equals("image/png")
        );
    }

    private void validateImageSize(Long imageSize) {
        if (imageSize == null || imageSize <= 0) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_SIZE);
        }

        if (imageSize > MAX_IMAGE_SIZE) {
            throw new CustomException(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }
    }
}